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

/** How a pause transition ended, decided inside the write (#1382 A11). */
enum class PauseTransition {
    /** The stored record took the transition, and the write landed. */
    APPLIED,

    /** A record is stored but it is another export's, corrupt, or in a phase that does not take it. */
    SKIPPED,

    /** No record is stored at all. */
    NO_RECORD,

    /** The disk write failed; nothing changed. */
    WRITE_FAILED,
}

/**
 * A change to the pending verdict (`AppStateKeys.MIGRATION_PENDING_VERDICT`) made inside the same
 * app_state edit as a pause write, so the two can never disagree (#1382 A01/A11). The verdict's
 * format belongs to the app module; this module only owns the edit.
 */
fun interface PendingVerdictEdit {
    fun apply(prefs: MutablePreferences)
}

/**
 * The durable message pause of a device-replacement export (#1382): one [MigrationPauseRecord] in
 * app_state, mirrored into [MessagingGate]. Every stored record pauses, one awaiting a decision
 * included; only a definite outcome (a cold start's proof that nothing was committed among them),
 * an explicit [clear], a logout or the login scope check removes it.
 *
 * Fails closed: the gate starts [MessagingPauseState.Loading] and stays closed until [load] has read
 * the record, and a record that cannot be decoded is a pause with an unknown owner that no
 * transition can resolve and no export can replace (#1382 A08). Every write commits to disk first
 * and then publishes the new state to the gate; writes are serialized and run non-cancellably, so a
 * write started by a cancelled caller still lands.
 */
@Singleton
class MigrationMessagePause @Inject constructor(
    @param:AppStateDataStore private val dataStore: DataStore<Preferences>,
    private val gate: MessagingGate,
) {

    private val writeMutex = Mutex()

    // Set by a logout that removed a pause; until the next login's scope check, every write keeps the
    // gate closed in memory. Guarded by writeMutex.
    private var logoutHold = false

    // The stored state, written under writeMutex before the gate is published.
    @Volatile
    private var stored: MessagingPauseState = MessagingPauseState.Loading

    val onChanged: StateFlow<MessagingPauseState> get() = gate.onChanged

    val state: MessagingPauseState get() = gate.state

    /** The stored record; null when there is none, before [load], or when the stored record is corrupt. */
    val record: MigrationPauseRecord? get() = (stored as? Paused)?.record

    /** True when a record is stored, a corrupt one included. */
    val hasRecord: Boolean get() = stored is Paused

    /** Starts [load] without waiting for it; called first thing at process start. */
    fun startLoad(scope: CoroutineScope = appScope) {
        scope.launch { load() }
    }

    /** Reads the record into the gate, retrying with backoff until a read succeeds. No-op once loaded. */
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

    /**
     * Starts [owner]'s pause in [MigrationPausePhase.EXPORTING] and closes the gate. Any stored
     * record refuses it, one awaiting a decision and a corrupt one included: a second export while
     * an earlier receiver may still register would split the account (#1382 A01).
     *
     * @return false when a record is stored or the write failed.
     */
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

    /**
     * The commit marker, written right before the container's end marker. [pending] (the receipt
     * to ask `/status` with) lands in the same edit, only when the marker is applied.
     */
    suspend fun committing(owner: String, sessionId: String, pending: PendingVerdictEdit? = null): PauseTransition =
        transform("upload_committing", pending) { it.committing(owner, sessionId) }

    /**
     * Applies a verdict to the record named by [owner] and/or [sessionId]. [onApplied] runs in the
     * same edit only when the record took the transition (the pending verdict's compare-and-delete).
     */
    suspend fun finish(
        owner: String?,
        sessionId: String?,
        outcome: MigrationPauseOutcome,
        onApplied: PendingVerdictEdit? = null,
    ): PauseTransition =
        // Named by session only, a record of another session is no record for this send at all.
        transform("verdict_${outcome.name.lowercase()}", onApplied, otherIsNoRecord = owner == null) {
            it.finishing(owner, sessionId, outcome)
        }

    /**
     * Runs [edit] on the pending verdict unless the stored record still waits on [sessionId], so a
     * dismissed page or a late cleanup never deletes a receipt a pause needs. False when the write failed.
     */
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

    /**
     * Clears the pause regardless of phase: [owner]'s only, or any record (including a corrupt one)
     * together with the pending verdict when [owner] is null, as a signed-out cold start does.
     */
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

    /**
     * Logout: removes the stored record of any owner and the pending verdict (#1382 A10), since the
     * login they wait on is ending. A pause in effect keeps messaging closed for the rest of this
     * process, which the logout ends. Opening here would let the ending login receive, ACK or send
     * before its credential is wiped; after a handoff the new app holds that same credential.
     */
    suspend fun clearForLogout(): Boolean = write("logout", keepClosed = true) { current, prefs ->
        prefs.remove(AppStateKeys.MIGRATION_PENDING_VERDICT)
        if (current is Paused) Open else current
    }

    /**
     * The scope check at load and at login: a record that the current login did not start is
     * cleared with the pending verdict. A corrupt record proves nothing about its login, so it stays
     * (#1382 A08); its exits are the auth probe's 401 and a logout.
     */
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

    /**
     * Applies a record transition, decided against the record read inside the write. A corrupt
     * record is [PauseTransition.SKIPPED], and so is another export's unless [otherIsNoRecord];
     * [alsoApply] runs in the same edit only when applied.
     */
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

    /**
     * Read-modify-write of the stored record, then publish to the gate; see the class KDoc. [step]
     * may also edit other app_state keys in the same edit. With [keepClosed], a pause removed from
     * disk stays closed in memory as an owner-less pause, and later writes keep it closed until a
     * write with [releaseHold] (the login scope check). Returns false only when the disk write failed.
     */
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
