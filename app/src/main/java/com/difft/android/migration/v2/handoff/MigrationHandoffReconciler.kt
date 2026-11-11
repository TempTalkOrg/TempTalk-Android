package com.difft.android.migration.v2.handoff

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.migration.v2.pause.SenderVerdictSettlement
import com.difft.android.migration.v2.pause.SettleResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton











@Singleton
class MigrationHandoffReconciler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userManager: StorageBoundUserManager,
    private val statusClient: MigrationStatusClient,
    private val completer: MigrationHandoffCompleter,
    
    private val settlement: SenderVerdictSettlement,
) {

    
    enum class Outcome {
        
        NOTHING,

        
        ALREADY_DONE,

        
        SIGNED_OUT,

        
        NOT_MIGRATED,

        
        UNKNOWN,
    }

    
    suspend fun onColdStart(): Outcome = reconcile("cold-start")

    
    suspend fun onSendFailure(): Outcome = reconcile("send-failure")

    
    suspend fun onScreenResumed(): Outcome = reconcile("screen-resumed")

    private suspend fun reconcile(trigger: String): Outcome {
        if (!PeerApps.isLegacy(context)) return Outcome.NOTHING
        val userData = userManager.getUserData() ?: return Outcome.NOTHING
        val handoffId = userData.migrationHandoffId
        val signedIn = !userData.baseAuth.isNullOrEmpty()

        if (userData.migrationHandoffDoneAt > 0) {
            
            
            return if (signedIn) {
                L.w { "[MigrationV2][Handoff] reconcile($trigger): interrupted sign-out — re-running" }
                completer.complete(handoffId)
                Outcome.SIGNED_OUT
            } else {
                Outcome.ALREADY_DONE
            }
        }

        
        if (handoffId.isEmpty()) return Outcome.NOTHING

        if (!signedIn) {
            
            
            L.i { "[MigrationV2][Handoff] reconcile($trigger): no credential left — clearing the handoff id" }
            clearHandoffId()
            return Outcome.NOTHING
        }

        return when (statusClient.queryHandoff(handoffId)) {
            HandoffResult.COMPLETED -> {
                L.i { "[MigrationV2][Handoff] reconcile($trigger): peer adopted the session — completing" }
                completer.complete(handoffId)
                Outcome.SIGNED_OUT
            }
            HandoffResult.UNKNOWN -> settleNotAdopted(handoffId, trigger)
            null -> {
                
                
                L.w { "[MigrationV2][Handoff] reconcile($trigger): peer unreachable — state unknown" }
                Outcome.UNKNOWN
            }
        }
    }

    









    private suspend fun settleNotAdopted(handoffId: String, trigger: String): Outcome {
        val result = settlement.settle(handoffId, null, MigrationPauseOutcome.FAILED, "handoff_reconcile")
        if (result == SettleResult.WRITE_FAILED) {
            L.w { "[MigrationV2][Handoff] reconcile($trigger): not adopted, pause write failed — id kept" }
            return Outcome.UNKNOWN
        }
        L.i { "[MigrationV2][Handoff] reconcile($trigger): not adopted — online result=${result.name.lowercase()}" }
        clearHandoffId()
        return Outcome.NOT_MIGRATED
    }

    private fun clearHandoffId() {
        userManager.update(commit = true) { migrationHandoffId = "" }
    }
}
