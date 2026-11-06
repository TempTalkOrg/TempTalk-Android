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

/**
 * Re-asks the receiver's `/status` while a pause awaits a decision (#1382 A07), owned by the app
 * rather than the send screen: every [INTERVAL_MS] while the app is in the foreground, with the one
 * pending verdict of that record's session, until a definite answer settles it. With no receipt
 * left (none was kept, or it outlived [PendingVerdictStore.MAX_AGE_MS]) only the auth probe remains,
 * and the record is not cleared for it.
 *
 * Only one question is in flight at a time: the loop and the send screen's re-check both go through
 * [probe]. The transfer's own `VerdictWatch` runs before the record reaches
 * [MigrationPausePhase.AWAITING_DECISION], so the two never poll together.
 */
@Singleton
class MigrationVerdictRecheck @Inject constructor(
    private val pause: MigrationMessagePause,
    private val pendingStore: PendingVerdictStore,
    private val settlement: SenderVerdictSettlement,
) {
    /** One `/status` question to the receiver; a seam for tests. */
    internal var probeVerdict: suspend (SenderSessionRef) -> Verdict = VerdictProbe::check

    private val probeMutex = Mutex()
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope = appScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch { run(foregroundStates()) }
    }

    /** One `/status` question, never concurrent with another from this process. */
    suspend fun probe(session: SenderSessionRef): Verdict = probeMutex.withLock { probeVerdict(session) }

    /** Re-checks while [foreground] is true and the stored record awaits a decision. */
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
                // A read that failed is not "no receipt": the pause state does not change, so
                // collectLatest would never restart this loop. Try again on the next beat.
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
            // A settled record changes the pause state, which ends this loop through collectLatest.
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
        /** The auth probe's period: both questions run on the same foreground beat. */
        const val INTERVAL_MS = 10_000L
        const val SOURCE = "foreground_recheck"
    }
}
