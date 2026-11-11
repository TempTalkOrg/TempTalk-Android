package com.difft.android.migration.v2.pause

import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.SenderVerdictTracker
import com.difft.android.migration.v2.transfer.Verdict
import com.difft.android.p2p.engine.model.ReceiverCaps






internal class SenderPauseRun(
    val session: SenderPauseSession?,
    private val settlement: SenderVerdictSettlement,
) {
    
    @Volatile
    var askedCancel: SenderVerdictTracker.CancelDecision? = null

    
    @Volatile
    var sessionId: String? = null
        private set

    
    @Volatile
    private var receiverRetainsVerdict = false

    fun onSessionPrepared(ref: SenderSessionRef) {
        sessionId = ref.sessionId
        receiverRetainsVerdict = ReceiverCaps.VERDICT_RETAIN_V1 in ref.caps
        session?.onSessionPrepared(ref)
    }

    










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

    
    suspend fun settleVerdict(verdict: Verdict) = settle(SenderVerdictSettlement.outcomeOf(verdict), "sender_verdict")

    
    suspend fun settleFailure() =
        settle(SenderVerdictSettlement.outcomeOfCancel(null, session?.abortAnswer), "sender_failure")

    
    suspend fun settleCancel() =
        settle(SenderVerdictSettlement.outcomeOfCancel(askedCancel, session?.abortAnswer), "sender_cancel")

    



    private suspend fun settle(outcome: MigrationPauseOutcome, source: String): SettleResult {
        val pauseSession = session
        
        if (pauseSession != null && !pauseSession.began) return SettleResult.NO_PAUSE
        val decided = if (outcome == MigrationPauseOutcome.UNKNOWN && pauseSession != null && !pauseSession.endMarkerWritten) {
            MigrationPauseOutcome.NOT_COMMITTED
        } else {
            outcome
        }
        return settlement.settle(pauseSession?.owner, sessionId, decided, source)
    }
}
