package com.difft.android.migration.v2.handoff

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import javax.inject.Inject
import javax.inject.Singleton










@Singleton
class MigrationHandoffCompleter @Inject constructor(
    private val userManager: UserManager,
    private val logoutManager: LogoutManager,
) {

    



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
