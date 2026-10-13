package com.difft.android.migration.v2.handoff

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.migration.v2.model.NeutralCredential
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a session credential is written to secure storage outside of sign-in.
 *
 * The freshness re-read sits inside `UserManager.update` so the read and the write are one critical
 * section; outside it would be a TOCTOU, because promote can take minutes and an ordinary sign-in can
 * complete in that window. A skipped install returns [Result.SkippedNotFresh], which the caller turns
 * into a non-200, so the sending app is never told it may wipe.
 *
 * Nothing here is ever logged: every field of [NeutralCredential] is live credential material.
 */
@Singleton
class CredentialInstaller @Inject constructor(private val userManager: UserManager) {

    sealed interface Result {
        /** The six session fields and the handoff marker were written. */
        data object Installed : Result

        /** An account appeared while the restore was running — nothing was written. */
        data object SkippedNotFresh : Result

        /** This same credential is already on disk from an earlier attempt — nothing to write. */
        data object AlreadyInstalled : Result
    }

    fun install(credential: NeutralCredential): Result {
        var result: Result = Result.SkippedNotFresh
        userManager.update(commit = true) {
            // Re-read inside the block: this is the freshness test, not a convenience.
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
