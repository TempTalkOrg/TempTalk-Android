package com.difft.android.migration.v2.transfer.send

import com.difft.android.migration.v2.export.ExportEstimate
import com.difft.android.migration.v2.handoff.MigrationHandoffReconciler

/**
 * MVI contract for the send screen: the device that scans the new device's QR and streams its export.
 * The mirror of [MigrationP2pReceiveContract] in the opposite role.
 *
 * `Idle`, `Scanning` and `Ready` are local pre-transfer states; the rest are mapped from the
 * role-agnostic transfer state held by the service, so the screen re-attaches to an in-flight send
 * after Activity death. The ephemeral transfer key is never carried in any state field.
 */
sealed interface MigrationP2pSendState {

    /** Before scanning — show the explainer + the scan button. */
    data object Idle : MigrationP2pSendState

    /** Scanner launched; awaiting result (transient). */
    data object Scanning : MigrationP2pSendState

    /** QR scanned and decoded; the target summary is shown. The transfer key is never surfaced. */
    /**
     * Paired: [alias] is the new device's name; [estimate] arrives asynchronously (null while it is
     * being computed); [includeAttachments] is the user's choice, on by default.
     */
    data class Ready(
        val alias: String,
        val estimate: ExportEstimate? = null,
        val includeAttachments: Boolean = true,
        /**
         * How much more free space this device needs for the currently selected payload; 0 when it
         * fits, and 0 while no verdict applies — only the same-device path can answer it at all,
         * because only there do both apps share one storage volume.
         */
        val spaceShortfallBytes: Long = 0,
    ) : MigrationP2pSendState {

        /** Start is blocked while this is false; the attachment toggle is the offered remedy. */
        val spaceSufficient: Boolean get() = spaceShortfallBytes <= 0

        /** Leaving attachments behind is a remedy only when the user was offered the choice at all. */
        val canDropAttachments: Boolean get() = estimate?.offersAttachmentChoice == true
    }

    /**
     * Credential handoff only: a user-presence check is on screen (the app's own App Lock, or the
     * system device credential). Handing the session over is irreversible from the user's point of
     * view, so the confirmation page is not reachable until it passes.
     */
    data object Authenticating : MigrationP2pSendState

    /**
     * Credential handoff only: this install may not hand its session over after all — the kill
     * switch is off, or the local session is no longer complete. Terminal; the only exit is back to
     * the new app. [reason] is a wire token so the screen resolves it exactly like a peer reject.
     */
    data class Blocked(val reason: String) : MigrationP2pSendState

    /** FGS preparing the upload (snapshot + prepare-upload handshake); brief. */
    data object Preparing : MigrationP2pSendState

    /**
     * Desktop link only: the Desktop is asking its user to confirm an account switch before it can
     * accept the upload, and the sender keeps knocking until it does.
     */
    data object AwaitingPeerConfirm : MigrationP2pSendState

    /** Bytes streaming. [progress] in `[0, 1]`, or `-1f` for indeterminate. */
    data class Transferring(val progress: Float, val totalBytes: Long = 0L) : MigrationP2pSendState

    /**
     * Every byte is on the wire; the receiver is verifying and importing and has not answered yet. A
     * bar frozen at 100% reads as a hang, so the screen names the wait instead. Ends in [Done] or
     * [Failed] when the verdict arrives.
     */
    data object AwaitingVerdict : MigrationP2pSendState

    /**
     * The result is not known and must not be guessed. [Phase.WAITING] while the receiver is still
     * being asked and has merely stopped reporting progress; [Phase.IDLE] once the service gave up
     * waiting; [Phase.CHECKING] during the user's own "check again"; [Phase.UNREACHABLE] when that
     * check could not get an answer. None of these offers a retry: the new device may have finished.
     */
    data class Unconfirmed(val phase: Phase) : MigrationP2pSendState {
        enum class Phase { WAITING, IDLE, CHECKING, UNREACHABLE }
    }

    /**
     * The receiver's delayed 200 arrived, confirming the migration on the new device. Terminal. This
     * device does not sign itself out: a phone receiver's registration revokes this session and the
     * app-wide kick-out detection handles it, while a desktop receiver only links.
     */
    data object Done : MigrationP2pSendState

    /**
     * Terminal failure; [reason] maps to a user-facing string, including the gate-reject token.
     *
     * In credential-handoff mode the restart is gated: a send that failed without a 200 may still
     * have been completed by the new app, and starting over while it is already live would put two
     * live sessions on one account. [retryEnabled] stays false until [reconcileOutcome] answers.
     */
    data class Failed(
        val reason: String,
        val retryEnabled: Boolean = true,
        val reconcileOutcome: MigrationHandoffReconciler.Outcome? = null,
    ) : MigrationP2pSendState {

        /** The peer is being asked right now — show the recovering copy and offer nothing yet. */
        val reconciling: Boolean get() = !retryEnabled && reconcileOutcome == null
    }

    /** User cancelled the in-flight send. */
    data object Cancelled : MigrationP2pSendState
}

sealed interface MigrationP2pSendIntent {
    data object LaunchScan : MigrationP2pSendIntent
    data class QrScanned(val raw: String) : MigrationP2pSendIntent

    /** The scanner returned without a result; go back to Idle. */
    data object ScanCancelled : MigrationP2pSendIntent
    data object Send : MigrationP2pSendIntent
    data object Cancel : MigrationP2pSendIntent

    /** Back out before or after a terminal state; finishes the Activity. */
    data object Close : MigrationP2pSendIntent

    /** From [MigrationP2pSendState.Ready]: carry attachment bytes, or leave them behind as pointers. */
    data class SetIncludeAttachments(val include: Boolean) : MigrationP2pSendIntent

    /** From [MigrationP2pSendState.Done] on the same device: bring the new app back to the front. */
    data object ReturnToPeer : MigrationP2pSendIntent

    /** The user-presence check passed; continue to the confirmation page. */
    data object AuthSucceeded : MigrationP2pSendIntent

    /** The user-presence check was dismissed or failed; the handoff is off. */
    data object AuthFailed : MigrationP2pSendIntent

    /** Handoff failure screen: ask the new app again whether it nonetheless finished. */
    data object RecheckPeer : MigrationP2pSendIntent

    /** Unconfirmed page: ask the receiver's `/status` once more. */
    data object RecheckVerdict : MigrationP2pSendIntent

    /** Unconfirmed page: the user looked at the new device and nothing arrived; forget the pending send. */
    data object DiscardUnconfirmed : MigrationP2pSendIntent

    /** Desktop-link failure screen: leave and scan the computer's code again — its session is gone. */
    data object RescanLink : MigrationP2pSendIntent

    /** Storage block page: open the system storage settings so the user can free space. */
    data object OpenStorageSettings : MigrationP2pSendIntent

    /** Version block: open the update entry for the end that is too old. */
    data object OpenUpdate : MigrationP2pSendIntent

    /**
     * Same-device handoff failure screen: ask the new app for a fresh pairing payload. The scanned
     * one is session-scoped and dead once the receiver it came from went away, so re-sending it can
     * only fail again.
     */
    data object RefreshPairing : MigrationP2pSendIntent
}

sealed interface MigrationP2pSendEffect {
    /** Same device: ask the new app's trampoline to bring its receive screen forward, then finish. */
    data object ReturnToPeerApp : MigrationP2pSendEffect

    /** Same device: the send has started, bring the new app's receive screen forward but stay alive underneath. */
    data object BringPeerToFront : MigrationP2pSendEffect

    data object LaunchScanActivity : MigrationP2pSendEffect

    /** Desktop link: open the home scanner (which routes the computer's code to the sign-in page) and finish. */
    data object RescanLink : MigrationP2pSendEffect

    /** Handoff: verify with the app's own App Lock (the Activity owns the for-result launch). */
    data object RequestAppLockAuth : MigrationP2pSendEffect

    /** Handoff: verify with the system device credential (PIN / pattern / password / biometric). */
    data object RequestDeviceCredentialAuth : MigrationP2pSendEffect
    data class Toast(val msg: String) : MigrationP2pSendEffect
    data object Finish : MigrationP2pSendEffect
    data object OpenStorageSettings : MigrationP2pSendEffect
    data object OpenUpdatePage : MigrationP2pSendEffect

    /** Same device: ask the new app's trampoline for a fresh pairing payload and re-pair with it. */
    data object RequestFreshPairing : MigrationP2pSendEffect
}
