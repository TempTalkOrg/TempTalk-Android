package com.difft.android.base.migration

import android.content.Context
import android.content.Intent

/**
 * Cross-module bridge that lets the `:login` verify flow drive the migration import UI without
 * depending on `:app`, where the restore engine and the import UI live. Interface in `:base`,
 * `@Binds` implementation in `:app`, the same shape as `ActivityProvider` / `LogoutManager`.
 *
 * The import stays a branch off the login main path: `:login` never references the restore engine,
 * it only probes [pendingRestoreSignIn] and launches an intent for result. Everything heavy — the
 * file picker, passphrase entry, progress, validate, import and promote — happens inside the `:app`
 * Activity the intent launches.
 */
interface MigrationImportLauncher {

    /**
     * True only when no user account has ever been established on this install — the net-install
     * precondition shared by the import prompt and the restore guard.
     *
     * A full logout and an uninstall reset the local `account` to empty, while a passive server
     * device-kick deliberately preserves it, so an empty `account` tells "never used here" apart from
     * "used here, including after a kick". The field is written at sign-in, not at verify, so at
     * prompt time a fresh install reads empty and a used device reads the prior account.
     *
     * Any read error resolves to false, so a transient failure never wrongly allows an import.
     */
    fun isFreshInstallTarget(): Boolean

    /**
     * Non-null when a prior restore already promoted an account's data to disk but its sign-in did not
     * complete. The caller compares [PendingRestoreSignIn.account] with the account signing in: the
     * same account skips the import prompt and continues sign-in against the data already on disk; a
     * different account means the data on disk is not theirs and the different-account guard must
     * reject the sign-in.
     *
     * [PendingRestoreSignIn.useInstalledIdentity] is false once publishing the installed identity
     * already failed, so the sign-in generates a fresh one instead of retrying material that failed.
     * Reads the phase flag only; any read error or a flag with no account resolves to null.
     */
    fun pendingRestoreSignIn(): PendingRestoreSignIn?

    /**
     * Records that publishing the installed identity failed during a restore-path sign-in, so the next
     * attempt publishes a freshly generated one instead. This keeps a single bad key from locking the
     * login forever. A failure to record is logged and swallowed; it must never block login.
     */
    fun markRestoreIdentityPublishFailed()

    /**
     * Records that a restore-path sign-in succeeded: the restore flag leaves `SIGNING_IN`, so no
     * future login of this account is pulled back onto the restore path, and moves to `COMPLETED`,
     * which the next cold start consumes to show the one-shot completion page. Call it only on a
     * successful restore-path sign-in and before the process is killed by `restartApp()`, so the
     * state survives the cold start.
     *
     * A write failure is logged and swallowed; the flag then lingers at `SIGNING_IN` and the next
     * login re-runs the restore continuation, which is idempotent.
     */
    fun markRestoreSignInComplete()

    /**
     * Whether this package takes imports at all. Both families do: the retiring `org.difft.chative`
     * mainly sends (to another device, or to the new app on this one), but it receives too, so that
     * every direction can be exercised on the builds CI produces. Compatibility with later container
     * versions is the handshake gate's job, not this switch's.
     */
    fun isImportOffered(): Boolean

    /**
     * Whether the P2P "receive from your old device" method is offered in the import chooser. File is
     * always offered; the P2P card appears only when this is true. It is false when the rollout flag
     * is off, when there is no usable LAN for a same-network peer, or in the same-device package
     * change, where there is no second device to pair with.
     *
     * Any uncertainty resolves to false, leaving File, which always works. `:login` never sees `:p2p`
     * types; it gets this boolean and the intent below.
     */
    fun isP2pMigrationOffered(context: Context): Boolean

    /**
     * Builds the intent that launches the P2P receive Activity, where this device shows a QR and the
     * old device scans and streams. Same launch-for-result contract as [createImportIntent], so both
     * paths route through [parseResult] into the identical sign-in and restart tail.
     *
     * @param account the logging-in account; the receiver advertises it in the QR and the handshake
     *   gate requires the source to match it.
     * @param sameDevice the receiver listens on loopback and hands its pairing payload to the old app
     *   by intent instead of showing a QR.
     * @param scanDesktop the Desktop shows the code, so the page opens the scanner at once.
     * @param pendingSignIn the verified sign-in this import is being done for; the receive page
     *   finishes it itself once the data is promoted, so the user never returns to the code page.
     */
    fun createP2pReceiveIntent(
        context: Context,
        account: String,
        sameDevice: Boolean = false,
        scanDesktop: Boolean = false,
        pendingSignIn: android.os.Parcelable? = null,
    ): Intent

    /**
     * The full-page source chooser shown after the code is verified: another phone or a computer. The
     * old app on this device is not offered here — that path lives before sign-in, on the first-launch
     * page.
     *
     * The chooser opens the pairing page itself and stays on the back stack behind it, so backing out
     * of the pairing page lands on the chooser directly instead of flashing this screen in between.
     * It therefore answers only [RESULT_RESTORED] or [RESULT_SKIPPED]; anything else means ask again.
     *
     * @param pendingSignIn the verified sign-in to hand to the pairing page, in the same
     *   `:base`-safe [android.os.Parcelable] form [createP2pReceiveIntent] takes it.
     */
    fun createSourceChooserIntent(
        context: Context,
        account: String,
        pendingSignIn: android.os.Parcelable? = null,
    ): Intent

    /**
     * Maps the Activity result into a typed [MigrationImportOutcome]. Anything unrecognised or missing
     * becomes [MigrationImportOutcome.Cancelled], so the caller falls back to normal sign-in.
     */
    fun parseResult(resultCode: Int, data: Intent?): MigrationImportOutcome

    companion object {
        /** The user finished a successful restore: data is on disk, continue sign-in. */
        const val RESULT_RESTORED = 1001

        /** The user chose to skip the import; continue normal sign-in. */
        const val RESULT_SKIPPED = 1002
    }
}

/**
 * A restore that promoted [account]'s data to disk and still owes its sign-in. See
 * [MigrationImportLauncher.pendingRestoreSignIn].
 *
 * @param account the account the data on disk belongs to (as verified against the container manifest).
 * @param useInstalledIdentity whether the sign-in should publish the identity the restore installed
 *   (true) or generate a fresh one because publishing the installed one already failed (false).
 */
data class PendingRestoreSignIn(
    val account: String,
    val useInstalledIdentity: Boolean,
)

/**
 * Typed outcome the `:login` side routes on after the import Activity returns. Deliberately small:
 * the import Activity owns all error display and retry, so by the time it returns the user has either
 * succeeded or explicitly chosen to continue with a normal login.
 */
sealed interface MigrationImportOutcome {

    /**
     * Restore succeeded: the database is promoted, attachments are in place and the restore flag is at
     * `SIGNING_IN`. The caller must continue sign-in with the same account and verification code and,
     * on success, restart the app rather than navigate — the promoted database needs a cold start.
     */
    data object Restored : MigrationImportOutcome

    /**
     * The user declined, skipped or cancelled the import. The caller continues the normal sign-in.
     */
    data object Cancelled : MigrationImportOutcome
}
