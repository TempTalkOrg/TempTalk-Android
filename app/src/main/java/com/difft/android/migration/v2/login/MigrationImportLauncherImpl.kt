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

/**
 * `:app`-side implementation of the [MigrationImportLauncher] bridge: the only place the `:login`
 * verify flow reaches the migration restore engine, through this Hilt binding rather than a direct
 * `:login → :app` dependency (impossible — `:app` depends on `:login`).
 *
 * Holds almost no logic: the resume probe reads the phase flag; the intents target the P2P
 * receive / send Activities, which own the transfer UX.
 */
@Singleton
class MigrationImportLauncherImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userManager: UserManager,
) : MigrationImportLauncher {

    /**
     * Fresh iff the local `account` is null/empty — empty after a fresh install, an uninstall or a
     * full logout, but preserved through a passive device-kick (`clearAuthOnly`). A read error means
     * not fresh, so a transient failure never wrongly allows import.
     */
    override fun isFreshInstallTarget(): Boolean =
        runCatching { userManager.getUserData()?.account.isNullOrEmpty() }
            .getOrElse { e ->
                L.w { "[MigrationV2][ImportLauncher] account read failed, treating as NOT fresh: ${e.stackTraceToString()}" }
                false
            }

    /**
     * The flag sits at [RestorePhase.SIGNING_IN] iff a prior attempt already promoted data to disk
     * and only sign-in remains. One flag read — no DB, no network. A read error yields null, so the
     * flow falls back to a fresh login that re-prompts for import.
     */
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
            // Nothing to bind a login to: neither continue the restore for anyone nor block anyone.
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

    /**
     * Advance the flag to [RestorePhase.COMPLETED] after a successful restore-path sign-in;
     * `MainActivity` consumes it on the next cold start to show the success page once. A write
     * failure never blocks login: the flag lingers at `SIGNING_IN` and the next login retries.
     */
    override fun markRestoreSignInComplete() {
        runCatching { RestoreFlagStore(context).advance(RestorePhase.COMPLETED) }
            .onFailure { e ->
                L.w { "[MigrationV2][ImportLauncher] marking restore complete failed: ${e.stackTraceToString()}" }
            }
    }

    /** Every family receives. */
    override fun isImportOffered(): Boolean = true

    /**
     * Always offered: a LAN-address probe is unreliable in both directions (a VPN tunnel or a
     * carrier-NAT mobile address also looks like a LAN), and hiding the method reads as "migration
     * unavailable". Reachability is proven at the pairing step, which surfaces typed failures.
     */
    override fun isP2pMigrationOffered(context: Context): Boolean = p2pOffered()

    /** Kept as a named seam so the "always offered" decision stays explicit and testable. */
    internal fun p2pOffered(): Boolean = true

    /** Intent for the P2P receive Activity. Reached only when [isP2pMigrationOffered] returned true. */
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
            // RESULT_SKIPPED, RESULT_CANCELED, or anything unexpected → continue normal sign-in.
            else -> MigrationImportOutcome.Cancelled
        }

    companion object {
        /** Account extra read by the P2P receive Activity. */
        const val EXTRA_ACCOUNT = "extra_account"

        /** Receive Activity extra: loopback receiver handed to the old app on this device (no QR). */
        const val EXTRA_SAME_DEVICE = "extra_same_device"

        /** Receive Activity extra: the source is a Desktop showing the code, so open the scanner at once. */
        const val EXTRA_SCAN_DESKTOP = "extra_scan_desktop"

        /** Receive Activity extra: the verified sign-in to finish with once the data is promoted. */
        const val EXTRA_PENDING_SIGN_IN = "extra_pending_sign_in"

        /**
         * Intent shapes for the two P2P screens, defined once. The transfer service builds the
         * notification's return-to-transfer intent without a launcher instance, so keeping the
         * shape here stops the two from drifting.
         */
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
