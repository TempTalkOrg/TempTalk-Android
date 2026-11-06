package com.difft.android.migration.v2.pause

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MigrationMessagePause
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.migration.pause.PauseTransition
import com.difft.android.base.migration.pause.PendingVerdictEdit
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderVerdictTracker
import com.difft.android.migration.v2.transfer.Verdict
import com.difft.android.p2p.engine.model.CancelState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a settlement did (#1382 A11). */
enum class SettleResult {
    /** The pause took the outcome inside its write. */
    APPLIED,

    /** No pause belongs to this send (a Desktop link, or a session with no record). */
    NO_PAUSE,

    /** The record was cleared or belongs to another export: no cleanup, no terminal on screen. */
    SKIPPED,

    /** The pause write failed: the pending verdict stays for the next question. */
    WRITE_FAILED,
    ;

    /** Only an applied outcome, or one with no pause behind it, may clean up and reach the screen. */
    val proceeds: Boolean get() = this == APPLIED || this == NO_PAUSE
}

/**
 * The one path by which a send's outcome reaches the message pause and the pending verdict
 * (#1382): the transfer's own end, the receiver's answer to a cancel, the foreground re-check, the
 * page's re-check, cold-start recovery and the handoff reconciler all settle here.
 *
 * The owner, session and phase are checked inside the pause's write, and the pending verdict of
 * [sessionId] is deleted in that same edit when the outcome is definite, so a failed or skipped
 * write leaves it for the next question. Runs non-cancellably: a settlement started by a cancelled
 * caller still commits.
 */
@Singleton
class SenderVerdictSettlement @Inject constructor(
    private val pause: MigrationMessagePause,
    private val pendingStore: PendingVerdictStore,
) {

    /**
     * @param owner the export attempt when the caller knows it; the record is then matched by owner.
     * @param sessionId the receiver session; names the record when [owner] is null, and always names
     *   the pending verdict a definite outcome deletes.
     */
    suspend fun settle(
        owner: String?,
        sessionId: String?,
        outcome: MigrationPauseOutcome,
        source: String,
    ): SettleResult = withContext(NonCancellable) {
        // Success, failure and a proven never-committed end all close the question for that session.
        val definite = outcome != MigrationPauseOutcome.UNKNOWN
        val deletePending = if (definite && sessionId != null) pendingStore.deleteIfSession(sessionId) else null
        val from = pause.record?.phase?.name?.lowercase() ?: "none"
        val transition = if (owner == null && sessionId == null) {
            PauseTransition.NO_RECORD
        } else {
            pause.finish(owner, if (owner == null) sessionId else null, outcome, deletePending)
        }
        val result = when (transition) {
            PauseTransition.APPLIED -> SettleResult.APPLIED
            PauseTransition.SKIPPED -> SettleResult.SKIPPED
            PauseTransition.WRITE_FAILED -> SettleResult.WRITE_FAILED
            // An export that had a pause and lost it was cleared under us; a send named only by
            // its session never had one.
            PauseTransition.NO_RECORD -> if (owner != null) SettleResult.SKIPPED else releaseWithoutPause(sessionId, deletePending)
        }
        val to = pause.record?.phase?.name?.lowercase() ?: "open"
        L.i {
            "[MigrationPause] event=settle source=$source outcome=${outcome.name.lowercase()} " +
                "from=$from to=$to result=${result.name.lowercase()}"
        }
        result
    }

    /**
     * Drops [sessionId]'s pending verdict unless a pause still waits on that session: dismissing the
     * unconfirmed page or a teardown never deletes the receipt an unresolved pause needs.
     */
    suspend fun releasePending(sessionId: String?): Unit = withContext(NonCancellable) {
        if (sessionId == null) return@withContext
        pause.editPendingUnlessHeld(sessionId, pendingStore.deleteIfSession(sessionId))
    }

    private suspend fun releaseWithoutPause(sessionId: String?, deletePending: PendingVerdictEdit?): SettleResult {
        if (deletePending == null) return SettleResult.NO_PAUSE
        return if (pause.editPendingUnlessHeld(sessionId, deletePending)) SettleResult.NO_PAUSE else SettleResult.WRITE_FAILED
    }

    companion object {
        /** A verdict the receiver reported on `/status`; see `VerdictWatch.verdictOf` for the vocabulary. */
        fun outcomeOf(verdict: Verdict): MigrationPauseOutcome = when (verdict) {
            Verdict.Succeeded -> MigrationPauseOutcome.SUCCESS
            is Verdict.Ended -> MigrationPauseOutcome.FAILED
            is Verdict.Unknown -> MigrationPauseOutcome.UNKNOWN
        }

        /**
         * A stopped send, by what the receiver answered to `/cancel`: only `stopped` or
         * `rolled_back` is a definite failure; `too_late`, no answer or any other value is unknown
         * (cleared before the commit marker, awaiting a decision after it).
         */
        internal fun outcomeOfCancel(decision: SenderVerdictTracker.CancelDecision?, abortAnswer: String?): MigrationPauseOutcome =
            when (decision) {
                SenderVerdictTracker.CancelDecision.CANCELLED -> MigrationPauseOutcome.FAILED
                SenderVerdictTracker.CancelDecision.UNKNOWN,
                SenderVerdictTracker.CancelDecision.KEEP_WAITING -> MigrationPauseOutcome.UNKNOWN
                null -> when (abortAnswer) {
                    CancelState.STOPPED, CancelState.ROLLED_BACK -> MigrationPauseOutcome.FAILED
                    else -> MigrationPauseOutcome.UNKNOWN
                }
            }
    }
}
