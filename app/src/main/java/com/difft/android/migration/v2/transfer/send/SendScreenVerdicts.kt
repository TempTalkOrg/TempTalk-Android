package com.difft.android.migration.v2.transfer.send

import com.difft.android.migration.v2.pause.SenderVerdictSettlement
import com.difft.android.migration.v2.transfer.PendingVerdict
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.Verdict






internal class SendScreenVerdicts(
    private val store: PendingVerdictStore,
    private val settlement: SenderVerdictSettlement,
) {
    suspend fun load(): PendingVerdict? = store.load()

    suspend fun clear() = settlement.releasePending(store.load()?.session?.sessionId)

    
    suspend fun settle(session: SenderSessionRef, verdict: Verdict): Boolean = settlement.settle(
        owner = null,
        sessionId = session.sessionId,
        outcome = SenderVerdictSettlement.outcomeOf(verdict),
        source = "recheck",
    ).proceeds
}
