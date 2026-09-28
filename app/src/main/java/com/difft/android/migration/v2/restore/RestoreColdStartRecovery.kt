package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.model.RestorePhase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cold-start crash recovery for restore, run once on launch to finish or roll back a restore that was
 * interrupted mid-promote.
 *
 * A normal launch must pay almost nothing: [resumeIfNeeded] does a single `File.exists()` on the
 * promote marker and returns [Outcome.Nothing] with no DB open, no network, no key access and no flag
 * write. The marker, not the restore flag, is the trigger, because it exists only across the window
 * where the real DB might be half-replaced. A `SIGNING_IN` flag with no marker means the promote
 * already completed and only sign-in remains, so there is nothing to resume.
 *
 * When a marker is present this delegates to [PromoteStep.resumeIfPending], then advances the flag to
 * [RestorePhase.SIGNING_IN] on a clean finish, or clears it on a discard or an unrecoverable abort so
 * the caller re-prompts restore instead of signing in against an empty DB. It never signs in or
 * restarts.
 */
@Singleton
class RestoreColdStartRecovery @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** Outcome of a cold-start recovery pass, for the caller (A5 / startup) to act on. */
    sealed interface Outcome {
        /** No interrupted promote — the normal-launch fast path. The caller proceeds untouched. */
        data object Nothing : Outcome

        /**
         * A promote was resumed to completion: the real DB is on disk and the flag is at
         * [RestorePhase.SIGNING_IN], so the caller continues its sign-in flow rather than re-importing.
         */
        data object PromoteResumed : Outcome

        /**
         * The promote was unrecoverable and safely abandoned to the clean net-install state (marker
         * + staging discarded, flag cleared). The caller proceeds as a normal (empty) launch.
         */
        data object AbortedToCleanState : Outcome

        /**
         * Resume hit a RETRYABLE failure; the marker survives and the next cold start retries. The
         * caller proceeds as normal this launch (the real DB may be empty — net-install safe).
         */
        data class RetryableFailure(val error: MigrationException) : Outcome
    }

    /**
     * Cold-start recovery pass. **Fast path:** returns [Outcome.Nothing] immediately when no
     * promote-marker is present (the only side effect on a normal launch is one `File.exists()`).
     * Suspends only along the (rare) real-crash branch, where [PromoteStep.resumeIfPending] runs on
     * `Dispatchers.IO`.
     *
     */
    suspend fun resumeIfNeeded(
        promoteStep: PromoteStep = PromoteStep(context),
        flagStore: RestoreFlagStore = RestoreFlagStore(context),
    ): Outcome {
        // Fast path: one File.exists() syscall; with no marker, return now with no side effects.
        if (!PromoteMarkerStore(context).exists()) {
            return Outcome.Nothing
        }

        // A real mid-promote crash. Resume idempotently.
        L.i { "[MigrationV2][ColdStart] promote-marker present → resuming interrupted promote" }
        return try {
            when (promoteStep.resumeIfPending()) {
                PromoteStep.ResumeResult.ResumedWithData -> {
                    // The real DB is on disk, so advance to SIGNING_IN and let the caller sign in.
                    flagStore.advance(RestorePhase.SIGNING_IN)
                    L.i { "[MigrationV2][ColdStart] promote resumed → phase=SIGNING_IN (A5 to continue)" }
                    Outcome.PromoteResumed
                }
                PromoteStep.ResumeResult.DiscardedToCleanState -> {
                    // A half-promote was discarded, so no data is on disk. Signalling SIGNING_IN here
                    // would sign in against an empty DB; clear the flag so the caller re-prompts.
                    L.w { "[MigrationV2][ColdStart] promote discarded → clean net-install (A5 re-prompts restore)" }
                    flagStore.clear()
                    Outcome.AbortedToCleanState
                }
                PromoteStep.ResumeResult.NothingToResume -> {
                    // The marker vanished between the exists() check and the read: nothing to do.
                    Outcome.Nothing
                }
            }
        } catch (e: MigrationException.RestoreAbortedCleanState) {
            // Unrecoverable: PromoteStep already cleared the marker and discarded staging. Clear the
            // flag too, so the device is back at a clean net install and cold start cannot loop.
            L.w { "[MigrationV2][ColdStart] promote unrecoverable → clean net-install: ${e.message}" }
            flagStore.clear()
            Outcome.AbortedToCleanState
        } catch (e: MigrationException.RestoreFailed) {
            // Retryable: the marker survives and the next cold start retries. The real DB is empty
            // meanwhile, so nothing is at risk.
            L.e { "[MigrationV2][ColdStart] promote resume retryable failure (marker kept): ${e.stackTraceToString()}" }
            Outcome.RetryableFailure(e)
        }
    }
}
