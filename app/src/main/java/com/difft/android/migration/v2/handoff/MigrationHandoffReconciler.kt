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

/**
 * Resolves the one state the same-device handoff can get stuck in: the old app started sending, the
 * new app may or may not have finished, and the receipt never arrived here.
 *
 * This device never wipes its credential without a 200, so whenever the 200 is missing the new
 * package is asked directly instead of guessed at — on cold start, right after a send failure (the
 * dominant case: two live sessions otherwise, and the retry button stays disabled until this
 * answers), and when the failure screen resumes. With no handoff id on record nothing happens and
 * no binder call is made; only the retiring family reconciles at all.
 */
@Singleton
class MigrationHandoffReconciler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userManager: StorageBoundUserManager,
    private val statusClient: MigrationStatusClient,
    private val completer: MigrationHandoffCompleter,
    // A handoff's message pause is owned by its handoff id (#1382); a peer that adopted nothing ends it.
    private val settlement: SenderVerdictSettlement,
) {

    /** What the reconciliation concluded; the failure screen renders its retry affordance from this. */
    enum class Outcome {
        /** Nothing to do (no handoff on record, or an id cleared because it can no longer matter). */
        NOTHING,

        /** The handoff had already completed here; steady state, nothing to do. */
        ALREADY_DONE,

        /** The peer confirmed it adopted the session — this device signed itself out. */
        SIGNED_OUT,

        /** The peer is reachable and adopted nothing — this device stays online; retry is safe. */
        NOT_MIGRATED,

        /** The peer could not be reached — state is genuinely unknown; do not offer a retry yet. */
        UNKNOWN,
    }

    /** Cold start: the receipt may have been delivered while this process was dead. */
    suspend fun onColdStart(): Outcome = reconcile("cold-start")

    /** A send failed in handoff mode — decide, in-session, whether the peer nonetheless finished. */
    suspend fun onSendFailure(): Outcome = reconcile("send-failure")

    /** The send screen came back to the front on a failure, or the user asked to check again. */
    suspend fun onScreenResumed(): Outcome = reconcile("screen-resumed")

    private suspend fun reconcile(trigger: String): Outcome {
        if (!PeerApps.isLegacy(context)) return Outcome.NOTHING
        val userData = userManager.getUserData() ?: return Outcome.NOTHING
        val handoffId = userData.migrationHandoffId
        val signedIn = !userData.baseAuth.isNullOrEmpty()

        if (userData.migrationHandoffDoneAt > 0) {
            // A handoff already completed here. A credential still present means the sign-out was
            // interrupted — re-run it; both the marker write and the sign-out are idempotent.
            return if (signedIn) {
                L.w { "[MigrationV2][Handoff] reconcile($trigger): interrupted sign-out — re-running" }
                completer.complete(handoffId)
                Outcome.SIGNED_OUT
            } else {
                Outcome.ALREADY_DONE
            }
        }

        // Hot path: nothing was ever handed over from this install.
        if (handoffId.isEmpty()) return Outcome.NOTHING

        if (!signedIn) {
            // Already signed out for an unrelated reason (a kick, a manual logout). The id can only
            // mislead a later run, so drop it; there is no credential left to protect.
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
                // Unreachable / too old / timed out. Keep the id so a later trigger can ask again,
                // and do not let the user retry on an unknown peer state.
                L.w { "[MigrationV2][Handoff] reconcile($trigger): peer unreachable — state unknown" }
                Outcome.UNKNOWN
            }
        }
    }

    /**
     * The peer answered and adopted nothing: this device is still the live session, so the handoff's
     * message pause ends as failed (#1382). The handoff id is the pause's only recovery key (a handoff
     * has no `/status` receipt), so it is cleared only once the settlement resolved:
     * - APPLIED / NO_PAUSE: the pause is gone; clear the id, retry is safe.
     * - SKIPPED: the record belongs to another export or was already cleared; this id can no longer
     *   settle anything, so keeping it would only re-ask the peer forever. Clear it.
     * - WRITE_FAILED: the pause is still on; keep the id so the next trigger settles it, and report
     *   UNKNOWN so no retry is offered while paused.
     */
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
