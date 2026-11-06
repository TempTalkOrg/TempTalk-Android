package com.difft.android.migration.v2.transfer.send

import com.difft.android.migration.v2.pause.SenderVerdictSettlement
import com.difft.android.migration.v2.transfer.PendingVerdict
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.Verdict

/**
 * The send screen's access to the pending verdict (#1382). Every release and every settlement goes
 * through [SenderVerdictSettlement], so dismissing the unconfirmed page never deletes the receipt
 * a pause awaiting the receiver's decision still needs.
 */
internal class SendScreenVerdicts(
    private val store: PendingVerdictStore,
    private val settlement: SenderVerdictSettlement,
) {
    suspend fun load(): PendingVerdict? = store.load()

    suspend fun clear() = settlement.releasePending(store.load()?.session?.sessionId)

    /** True when the screen may show the verdict as final (see `SettleResult.proceeds`). */
    suspend fun settle(session: SenderSessionRef, verdict: Verdict): Boolean = settlement.settle(
        owner = null,
        sessionId = session.sessionId,
        outcome = SenderVerdictSettlement.outcomeOf(verdict),
        source = "recheck",
    ).proceeds
}
