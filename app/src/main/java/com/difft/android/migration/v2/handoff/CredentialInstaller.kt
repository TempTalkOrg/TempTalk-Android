package com.difft.android.migration.v2.handoff

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.migration.v2.model.NeutralCredential
import javax.inject.Inject
import javax.inject.Singleton











@Singleton
class CredentialInstaller @Inject constructor(private val userManager: UserManager) {

    sealed interface Result {
        
        data object Installed : Result

        
        data object SkippedNotFresh : Result

        
        data object AlreadyInstalled : Result
    }

    fun install(credential: NeutralCredential): Result {
        var result: Result = Result.SkippedNotFresh
        userManager.update(commit = true) {
            
            if (!account.isNullOrEmpty()) {
                if (account == credential.account && migrationHandoffCompletedId == credential.handoffId) {
                    result = Result.AlreadyInstalled
                }
                return@update
            }
            account = credential.account
            baseAuth = credential.baseAuth
            microToken = credential.microToken
            signalingKey = credential.signalingKey
            email = credential.email
            phoneNumber = credential.phoneNumber
            migrationHandoffCompletedId = credential.handoffId
            result = Result.Installed
        }
        when (result) {
            Result.SkippedNotFresh -> L.w { "[MigrationV2][Handoff] credential install skipped — an account appeared during promote" }
            Result.AlreadyInstalled -> L.i { "[MigrationV2][Handoff] credential already installed by an earlier attempt" }
            Result.Installed -> L.i { "[MigrationV2][Handoff] credential installed — this device is now signed in" }
        }
        return result
    }
}
