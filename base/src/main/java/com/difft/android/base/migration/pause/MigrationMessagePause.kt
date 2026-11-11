package com.difft.android.base.migration.pause

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingPauseState.Open
import com.difft.android.base.migration.pause.MessagingPauseState.Paused
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException


enum class PauseTransition {
    
    APPLIED,

    
    SKIPPED,

    
    NO_RECORD,

    
    WRITE_FAILED,
}






fun interface PendingVerdictEdit {
    fun apply(prefs: MutablePreferences)
}













@Singleton
class MigrationMessagePause @Inject constructor(
    @param:AppStateDataStore private val dataStore: DataStore<Preferences>,
    private val gate: MessagingGate,
) {

    private val writeMutex = Mutex()

    
    
    private var logoutHold = false

    
    @Volatile
    private var stored: MessagingPauseState = MessagingPauseState.Loading

    val onChanged: StateFlow<MessagingPauseState> get() = gate.onChanged

    val state: MessagingPauseState get() = gate.state

    
    val record: MigrationPauseRecord? get() = (stored as? Paused)?.record

    
    val hasRecord: Boolean get() = stored is Paused

    
    fun startLoad(scope: CoroutineScope = appScope) {
        scope.launch { load() }
    }

    
    suspend fun load() {
        var attempt = 0
        while (gate.state == MessagingPauseState.Loading) {
            val loaded = writeMutex.withLock {
                if (gate.state != MessagingPauseState.Loading) return@withLock true
                try {
                    val next = stateOf(dataStore.data.first()[AppStateKeys.MIGRATION_MESSAGE_PAUSE])
                    stored = next
                    gate.publish(next)
                    L.i { "[MigrationPause] event=load_done state=${MessagingGate.describe(next)} attempts=${attempt + 1}" }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    attempt++
                    L.w { "[MigrationPause] event=load_failed attempt=$attempt error=${e.javaClass.simpleName}" }
                    false
                }
            }
            if (!loaded) delay(backoffMs(attempt))
        }
    }

    






    suspend fun begin(
        owner: String,
        accountScope: String,
        credentialHash: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val record = MigrationPauseRecord(owner, MigrationPausePhase.EXPORTING, accountScope, credentialHash, null, now)
        var began = false
        val committed = write("begin") { current, _ ->
            if (current == Open) {
                began = true
                Paused(record)
            } else {
                current
            }
        }
        if (committed && !began) L.w { "[MigrationPause] event=begin_rejected owner=${owner.take(8)} reason=record_exists" }
        return committed && began
    }

    



    suspend fun committing(owner: String, sessionId: String, pending: PendingVerdictEdit? = null): PauseTransition =
        transform("upload_committing", pending) { it.committing(owner, sessionId) }

    



    suspend fun finish(
        owner: String?,
        sessionId: String?,
        outcome: MigrationPauseOutcome,
        onApplied: PendingVerdictEdit? = null,
    ): PauseTransition =
        
        transform("verdict_${outcome.name.lowercase()}", onApplied, otherIsNoRecord = owner == null) {
            it.finishing(owner, sessionId, outcome)
        }

    



    suspend fun editPendingUnlessHeld(sessionId: String?, edit: PendingVerdictEdit): Boolean {
        var held = false
        val written = write("pending_release") { current, prefs ->
            held = sessionId != null && (current as? Paused)?.record?.sessionId == sessionId
            if (!held) edit.apply(prefs)
            current
        }
        if (held) L.i { "[MigrationPause] event=pending_verdict_kept reason=held_by_pause" }
        return written
    }

    



    suspend fun clear(owner: String?, reason: String): Boolean = write(reason) { current, prefs ->
        val paused = current as? Paused ?: return@write current
        when {
            owner == null -> {
                prefs.remove(AppStateKeys.MIGRATION_PENDING_VERDICT)
                Open
            }
            paused.record?.owner == owner -> Open
            else -> current
        }
    }

    





    suspend fun clearForLogout(): Boolean = write("logout", keepClosed = true) { current, prefs ->
        prefs.remove(AppStateKeys.MIGRATION_PENDING_VERDICT)
        if (current is Paused) Open else current
    }

    




    suspend fun discardIfOutOfScope(accountScope: String, credentialHash: String): Boolean =
        write("login_changed", releaseHold = true) { current, prefs ->
            val record = (current as? Paused)?.record ?: return@write current
            if (record.matchesScope(accountScope, credentialHash)) {
                current
            } else {
                prefs.remove(AppStateKeys.MIGRATION_PENDING_VERDICT)
                Open
            }
        }

    




    private suspend fun transform(
        reason: String,
        alsoApply: PendingVerdictEdit?,
        otherIsNoRecord: Boolean = false,
        step: (MigrationPauseRecord) -> PauseStep,
    ): PauseTransition {
        var result = PauseTransition.NO_RECORD
        var skipReason: String? = null
        val written = write(reason) { current, prefs ->
            val paused = current as? Paused ?: return@write current
            val decided = paused.record?.let(step) ?: PauseStep.Skipped("corrupt")
            when (decided) {
                is PauseStep.Skipped -> {
                    result = PauseTransition.SKIPPED
                    skipReason = decided.reason
                    current
                }
                PauseStep.OtherExport -> {
                    result = if (otherIsNoRecord) PauseTransition.NO_RECORD else PauseTransition.SKIPPED
                    skipReason = "other_export"
                    current
                }
                is PauseStep.Applied -> {
                    result = PauseTransition.APPLIED
                    alsoApply?.apply(prefs)
                    decided.next?.let(::Paused) ?: Open
                }
            }
        }
        if (!written) return PauseTransition.WRITE_FAILED
        skipReason?.let { why -> L.i { "[MigrationPause] event=transition_skipped reason=$reason why=$why" } }
        return result
    }

    





    private suspend fun write(
        reason: String,
        keepClosed: Boolean = false,
        releaseHold: Boolean = false,
        step: (MessagingPauseState, MutablePreferences) -> MessagingPauseState,
    ): Boolean =
        withContext(NonCancellable) {
            writeMutex.withLock {
                var from: MessagingPauseState = Open
                var to: MessagingPauseState = Open
                try {
                    dataStore.edit { prefs ->
                        from = stateOf(prefs[AppStateKeys.MIGRATION_MESSAGE_PAUSE])
                        to = step(from, prefs)
                        if (to != from) {
                            when (val next = to) {
                                is Paused -> next.record?.let { prefs[AppStateKeys.MIGRATION_MESSAGE_PAUSE] = MigrationPauseRecordCodec.encode(it) }
                                else -> prefs.remove(AppStateKeys.MIGRATION_MESSAGE_PAUSE)
                            }
                        }
                    }
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    L.e { "[MigrationPause] event=write_failed reason=$reason ${e.stackTraceToString()}" }
                    return@withLock false
                }
                if (keepClosed && from is Paused && to == Open) logoutHold = true
                if (releaseHold) logoutHold = false
                stored = to
                gate.publish(if (logoutHold && to == Open) Paused(null) else to)
                if (to != from) logChange(reason, from, to)
                true
            }
        }

    private fun logChange(reason: String, from: MessagingPauseState, to: MessagingPauseState) {
        val owner = ((to as? Paused)?.record ?: (from as? Paused)?.record)?.owner?.take(8) ?: "unknown"
        if (from == Open && to is Paused) {
            L.i { "[MigrationPause] event=pause_begin owner=$owner phase=${MessagingGate.describe(to)}" }
        } else {
            L.i {
                "[MigrationPause] event=pause_changed reason=$reason owner=$owner " +
                    "from=${MessagingGate.describe(from)} to=${MessagingGate.describe(to)}"
            }
        }
    }

    private fun stateOf(raw: String?): MessagingPauseState =
        if (raw == null) Open else Paused(MigrationPauseRecordCodec.decode(raw))

    private companion object {
        const val BACKOFF_BASE_MS = 500L
        const val BACKOFF_MAX_MS = 30_000L
        const val BACKOFF_MAX_SHIFT = 6

        fun backoffMs(attempt: Int): Long =
            (BACKOFF_BASE_MS shl (attempt - 1).coerceIn(0, BACKOFF_MAX_SHIFT)).coerceAtMost(BACKOFF_MAX_MS)
    }
}
