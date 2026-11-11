package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.model.RestorePhase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
















@Singleton
class RestoreColdStartRecovery @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    
    sealed interface Outcome {
        
        data object Nothing : Outcome

        



        data object PromoteResumed : Outcome

        



        data object AbortedToCleanState : Outcome

        



        data class RetryableFailure(val error: MigrationException) : Outcome
    }

    






    suspend fun resumeIfNeeded(
        promoteStep: PromoteStep = PromoteStep(context),
        flagStore: RestoreFlagStore = RestoreFlagStore(context),
    ): Outcome {
        
        if (!PromoteMarkerStore(context).exists()) {
            return Outcome.Nothing
        }

        
        L.i { "[MigrationV2][ColdStart] promote-marker present → resuming interrupted promote" }
        return try {
            when (promoteStep.resumeIfPending()) {
                PromoteStep.ResumeResult.ResumedWithData -> {
                    
                    flagStore.advance(RestorePhase.SIGNING_IN)
                    L.i { "[MigrationV2][ColdStart] promote resumed → phase=SIGNING_IN (A5 to continue)" }
                    Outcome.PromoteResumed
                }
                PromoteStep.ResumeResult.DiscardedToCleanState -> {
                    
                    
                    L.w { "[MigrationV2][ColdStart] promote discarded → clean net-install (A5 re-prompts restore)" }
                    flagStore.clear()
                    Outcome.AbortedToCleanState
                }
                PromoteStep.ResumeResult.NothingToResume -> {
                    
                    Outcome.Nothing
                }
            }
        } catch (e: MigrationException.RestoreAbortedCleanState) {
            
            
            L.w { "[MigrationV2][ColdStart] promote unrecoverable → clean net-install: ${e.message}" }
            flagStore.clear()
            Outcome.AbortedToCleanState
        } catch (e: MigrationException.RestoreFailed) {
            
            
            L.e { "[MigrationV2][ColdStart] promote resume retryable failure (marker kept): ${e.stackTraceToString()}" }
            Outcome.RetryableFailure(e)
        }
    }
}
