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


enum class SettleResult {
    
    APPLIED,

    
    NO_PAUSE,

    
    SKIPPED,

    
    WRITE_FAILED,
    ;

    
    val proceeds: Boolean get() = this == APPLIED || this == NO_PAUSE
}











@Singleton
class SenderVerdictSettlement @Inject constructor(
    private val pause: MigrationMessagePause,
    private val pendingStore: PendingVerdictStore,
) {

    




    suspend fun settle(
        owner: String?,
        sessionId: String?,
        outcome: MigrationPauseOutcome,
        source: String,
    ): SettleResult = withContext(NonCancellable) {
        
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
            
            
            PauseTransition.NO_RECORD -> if (owner != null) SettleResult.SKIPPED else releaseWithoutPause(sessionId, deletePending)
        }
        val to = pause.record?.phase?.name?.lowercase() ?: "open"
        L.i {
            "[MigrationPause] event=settle source=$source outcome=${outcome.name.lowercase()} " +
                "from=$from to=$to result=${result.name.lowercase()}"
        }
        result
    }

    



    suspend fun releasePending(sessionId: String?): Unit = withContext(NonCancellable) {
        if (sessionId == null) return@withContext
        pause.editPendingUnlessHeld(sessionId, pendingStore.deleteIfSession(sessionId))
    }

    private suspend fun releaseWithoutPause(sessionId: String?, deletePending: PendingVerdictEdit?): SettleResult {
        if (deletePending == null) return SettleResult.NO_PAUSE
        return if (pause.editPendingUnlessHeld(sessionId, deletePending)) SettleResult.NO_PAUSE else SettleResult.WRITE_FAILED
    }

    companion object {
        
        fun outcomeOf(verdict: Verdict): MigrationPauseOutcome = when (verdict) {
            Verdict.Succeeded -> MigrationPauseOutcome.SUCCESS
            is Verdict.Ended -> MigrationPauseOutcome.FAILED
            is Verdict.Unknown -> MigrationPauseOutcome.UNKNOWN
        }

        




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
