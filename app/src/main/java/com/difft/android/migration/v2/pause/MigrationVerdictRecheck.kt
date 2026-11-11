package com.difft.android.migration.v2.pause

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationMessagePause
import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.base.utils.appScope
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.Verdict
import com.difft.android.migration.v2.transfer.VerdictProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import util.AppForegroundObserver
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException












@Singleton
class MigrationVerdictRecheck @Inject constructor(
    private val pause: MigrationMessagePause,
    private val pendingStore: PendingVerdictStore,
    private val settlement: SenderVerdictSettlement,
) {
    
    internal var probeVerdict: suspend (SenderSessionRef) -> Verdict = VerdictProbe::check

    private val probeMutex = Mutex()
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope = appScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch { run(foregroundStates()) }
    }

    
    suspend fun probe(session: SenderSessionRef): Verdict = probeMutex.withLock { probeVerdict(session) }

    
    internal suspend fun run(foreground: Flow<Boolean>, intervalMs: Long = INTERVAL_MS) {
        combine(pause.onChanged, foreground) { state, inForeground -> if (inForeground) undecided(state) else null }
            .distinctUntilChanged()
            .collectLatest { target -> if (target != null) recheckUntilDecided(target, intervalMs) }
    }

    private suspend fun recheckUntilDecided(target: Undecided, intervalMs: Long) {
        while (true) {
            val pending = try {
                target.sessionId?.let { id -> pendingStore.loadOrThrow()?.takeIf { it.session.sessionId == id } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                
                
                L.w { "[MigrationPause] event=recheck_read_failed owner=${target.owner.take(8)} error=${e.javaClass.simpleName}" }
                delay(intervalMs)
                continue
            }
            if (pending == null) {
                L.i { "[MigrationPause] event=recheck_idle owner=${target.owner.take(8)} reason=no_receipt" }
                return
            }
            val verdict = probe(pending.session)
            val result = settlement.settle(target.owner, target.sessionId, SenderVerdictSettlement.outcomeOf(verdict), SOURCE)
            
            if (verdict !is Verdict.Unknown && result.proceeds) return
            delay(intervalMs)
        }
    }

    private data class Undecided(val owner: String, val sessionId: String?)

    private fun undecided(state: MessagingPauseState): Undecided? {
        val record = (state as? MessagingPauseState.Paused)?.record ?: return null
        return if (record.phase == MigrationPausePhase.AWAITING_DECISION) Undecided(record.owner, record.sessionId) else null
    }

    private fun foregroundStates(): Flow<Boolean> = callbackFlow {
        val listener = object : AppForegroundObserver.Listener {
            override fun onForeground() {
                trySend(true)
            }

            override fun onBackground() {
                trySend(false)
            }
        }
        trySend(AppForegroundObserver.isForegrounded())
        AppForegroundObserver.addListener(listener)
        awaitClose { AppForegroundObserver.removeListener(listener) }
    }

    internal companion object {
        
        const val INTERVAL_MS = 10_000L
        const val SOURCE = "foreground_recheck"
    }
}
