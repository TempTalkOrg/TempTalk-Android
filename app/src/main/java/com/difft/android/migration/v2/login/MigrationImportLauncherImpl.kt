package com.difft.android.migration.v2.login

import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.migration.MigrationImportOutcome
import com.difft.android.base.migration.PendingRestoreSignIn
import com.difft.android.base.user.UserManager
import com.difft.android.migration.model.RestoreInProgressFlag
import com.difft.android.migration.model.RestorePhase
import com.difft.android.migration.v2.restore.RestoreFlagStore
import com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveActivity
import com.difft.android.migration.v2.transfer.send.MigrationP2pSendActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.base.migration.PeerApps









@Singleton
class MigrationImportLauncherImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userManager: UserManager,
) : MigrationImportLauncher {

    




    override fun isFreshInstallTarget(): Boolean =
        runCatching { userManager.getUserData()?.account.isNullOrEmpty() }
            .getOrElse { e ->
                L.w { "[MigrationV2][ImportLauncher] account read failed, treating as NOT fresh: ${e.stackTraceToString()}" }
                false
            }

    




    override fun pendingRestoreSignIn(): PendingRestoreSignIn? =
        runCatching { RestoreFlagStore(context).read()?.let(::toPendingSignIn) }
            .getOrElse { e ->
                L.w { "[MigrationV2][ImportLauncher] flag read failed, treating as no-pending: ${e.stackTraceToString()}" }
                null
            }

    private fun toPendingSignIn(flag: RestoreInProgressFlag): PendingRestoreSignIn? {
        if (RestorePhase.fromName(flag.phase) != RestorePhase.SIGNING_IN) return null
        val account = flag.account
        if (account.isNullOrBlank()) {
            
            L.w { "[MigrationV2][ImportLauncher] SIGNING_IN flag carries no account — ignoring it" }
            return null
        }
        return PendingRestoreSignIn(account = account, useInstalledIdentity = !flag.identityPublishFailed)
    }

    override fun markRestoreIdentityPublishFailed() {
        runCatching { RestoreFlagStore(context).markIdentityPublishFailed() }
            .onFailure { e ->
                L.w { "[MigrationV2][ImportLauncher] marking identity publish failure failed: ${e.stackTraceToString()}" }
            }
    }

    




    override fun markRestoreSignInComplete() {
        runCatching { RestoreFlagStore(context).advance(RestorePhase.COMPLETED) }
            .onFailure { e ->
                L.w { "[MigrationV2][ImportLauncher] marking restore complete failed: ${e.stackTraceToString()}" }
            }
    }

    
    override fun isImportOffered(): Boolean = true

    




    override fun isP2pMigrationOffered(context: Context): Boolean = p2pOffered()

    
    internal fun p2pOffered(): Boolean = true

    
    override fun createP2pReceiveIntent(
        context: Context,
        account: String,
        sameDevice: Boolean,
        scanDesktop: Boolean,
        pendingSignIn: android.os.Parcelable?,
    ): Intent =
        createP2pReceiveIntentFor(context, account, sameDevice, scanDesktop).apply {
            if (pendingSignIn != null) putExtra(EXTRA_PENDING_SIGN_IN, pendingSignIn)
        }

    override fun createSourceChooserIntent(
        context: Context,
        account: String,
        pendingSignIn: android.os.Parcelable?,
    ): Intent =
        Intent(context, MigrationSourceChooserActivity::class.java).apply {
            putExtra(EXTRA_ACCOUNT, account)
            if (pendingSignIn != null) putExtra(EXTRA_PENDING_SIGN_IN, pendingSignIn)
        }

    override fun parseResult(resultCode: Int, data: Intent?): MigrationImportOutcome =
        when (resultCode) {
            MigrationImportLauncher.RESULT_RESTORED -> MigrationImportOutcome.Restored
            
            else -> MigrationImportOutcome.Cancelled
        }

    companion object {
        
        const val EXTRA_ACCOUNT = "extra_account"

        
        const val EXTRA_SAME_DEVICE = "extra_same_device"

        
        const val EXTRA_SCAN_DESKTOP = "extra_scan_desktop"

        
        const val EXTRA_PENDING_SIGN_IN = "extra_pending_sign_in"

        




        fun createP2pReceiveIntentFor(
            context: Context,
            account: String,
            sameDevice: Boolean = false,
            scanDesktop: Boolean = false,
            credentialHandoff: Boolean = false,
        ): Intent =
            Intent(context, MigrationP2pReceiveActivity::class.java).apply {
                putExtra(EXTRA_ACCOUNT, account)
                putExtra(EXTRA_SAME_DEVICE, sameDevice)
                putExtra(EXTRA_SCAN_DESKTOP, scanDesktop)
                putExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, credentialHandoff)
            }

        fun createP2pSendIntentFor(context: Context): Intent =
            Intent(context, MigrationP2pSendActivity::class.java)
    }
}
