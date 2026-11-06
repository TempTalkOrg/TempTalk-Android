package com.difft.android.migration.v2.pause

import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.SenderVerdictTracker
import com.difft.android.migration.v2.transfer.Verdict
import com.difft.android.p2p.engine.model.ReceiverCaps

/**
 * One sender run's side of the message pause (#1382): its [session] (null for a Desktop link) and
 * how each way the run ends is settled. Every settle returns the [SettleResult], and the caller
 * shows a definite terminal only when [SettleResult.proceeds].
 */
internal class SenderPauseRun(
    val session: SenderPauseSession?,
    private val settlement: SenderVerdictSettlement,
) {
    /** What the receiver answered to a cancel the user put to it while the verdict was outstanding. */
    @Volatile
    var askedCancel: SenderVerdictTracker.CancelDecision? = null

    /** The receiver session from `prepare-upload` on; names the pending verdict a definite end deletes. */
    @Volatile
    var sessionId: String? = null
        private set

    /** The receiver advertised [ReceiverCaps.VERDICT_RETAIN_V1] in `prepare-upload`. */
    @Volatile
    private var receiverRetainsVerdict = false

    fun onSessionPrepared(ref: SenderSessionRef) {
        sessionId = ref.sessionId
        receiverRetainsVerdict = ReceiverCaps.VERDICT_RETAIN_V1 in ref.caps
        session?.onSessionPrepared(ref)
    }

    /**
     * The upload's own answer. Only a 200 is success, and a 200 without a handoff's acknowledgment
     * is unknown (the handoff reconciler decides it). A receiver's typed rejection ([receiverRejected],
     * see `SenderHttpClient.typedRejection`) is a definite failure.
     *
     * Any other non-200 of a paused export depends on the receiver (#1382 A06): one that advertised
     * [ReceiverCaps.VERDICT_RETAIN_V1] still answers `/status` after a failure, so the outcome is
     * unknown and the re-check decides it. An older receiver stops answering once it failed, so
     * waiting on it would hold the pause until a logout; its non-200 is a failure. A transport
     * failure with no reply at all never reaches here and stays unknown either way.
     */
    suspend fun settleResult(ok: Boolean, handoffUnacknowledged: Boolean, receiverRejected: Boolean = false) = settle(
        when {
            ok && handoffUnacknowledged -> MigrationPauseOutcome.UNKNOWN
            ok -> MigrationPauseOutcome.SUCCESS
            receiverRejected -> MigrationPauseOutcome.FAILED
            session != null && !receiverRetainsVerdict -> MigrationPauseOutcome.FAILED
            else -> MigrationPauseOutcome.UNKNOWN
        },
        "sender_result",
    )

    /** The receiver's verdict from `/status`. */
    suspend fun settleVerdict(verdict: Verdict) = settle(SenderVerdictSettlement.outcomeOf(verdict), "sender_verdict")

    /** The run failed; the pipeline told the receiver to stop, and its answer decides. */
    suspend fun settleFailure() =
        settle(SenderVerdictSettlement.outcomeOfCancel(null, session?.abortAnswer), "sender_failure")

    /** The run was cancelled; the receiver's answer to the user's or the pipeline's cancel decides. */
    suspend fun settleCancel() =
        settle(SenderVerdictSettlement.outcomeOfCancel(askedCancel, session?.abortAnswer), "sender_cancel")

    /**
     * An unknown outcome of a run that provably never wrote the container's end marker is
     * [MigrationPauseOutcome.NOT_COMMITTED]: no receiver can commit without it (#1382 A01).
     */
    private suspend fun settle(outcome: MigrationPauseOutcome, source: String): SettleResult {
        val pauseSession = session
        // Refused before it paused (another record is stored): that record is not this run's to settle.
        if (pauseSession != null && !pauseSession.began) return SettleResult.NO_PAUSE
        val decided = if (outcome == MigrationPauseOutcome.UNKNOWN && pauseSession != null && !pauseSession.endMarkerWritten) {
            MigrationPauseOutcome.NOT_COMMITTED
        } else {
            outcome
        }
        return settlement.settle(pauseSession?.owner, sessionId, decided, source)
    }
}
