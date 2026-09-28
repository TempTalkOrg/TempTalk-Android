package com.difft.android.migration.v2.handoff

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The old app's half of the receipt: once the new package has returned a 200 — which it only does
 * after promoting the database and installing both the identity and the credential — this device
 * stops being a live session for that account.
 *
 * The durable marker is written first, with `commit = true`: it survives an interrupted sign-out,
 * stops the reconciler asking the peer again, and drives the "moved to the new app" page. The local
 * sign-out that follows makes no server call, so the account itself is untouched. Idempotent.
 */
@Singleton
class MigrationHandoffCompleter @Inject constructor(
    private val userManager: UserManager,
    private val logoutManager: LogoutManager,
) {

    /**
     * @param handoffId the handoff this receipt belongs to. The in-flight id is cleared only when it
     *   matches, so a receipt for a stale handoff can never drop a newer one that is still running.
     */
    suspend fun complete(handoffId: String) {
        val inFlight = userManager.getUserData()?.migrationHandoffId.orEmpty()
        userManager.update(commit = true) {
            migrationHandoffDoneAt = System.currentTimeMillis()
            if (inFlight.isEmpty() || inFlight == handoffId) migrationHandoffId = ""
        }
        L.i { "[MigrationV2][Handoff] receipt received — signing out locally (no server call)" }
        logoutManager.doMigrationHandoffSignOut()
    }
}
