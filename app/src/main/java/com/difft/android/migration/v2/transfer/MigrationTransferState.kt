package com.difft.android.migration.v2.transfer

/**
 * Role-agnostic transfer state held by [MigrationTransferStateHolder] and written only by
 * [MigrationTransferService]. It describes both roles and, because the holder is a process singleton
 * that outlives an Activity, carries no Android UI types — a `Bitmap` in a process static would leak
 * across Activity instances, so the view models re-render the QR from the payload themselves.
 *
 * The ephemeral transfer key is never carried here, only the non-secret ip/port/pin surface.
 */
sealed interface TransferState {

    /** No transfer in flight (initial / after [MigrationTransferStateHolder.reset]). */
    data object Idle : TransferState

    /** Bringing up the server (receiver) or preparing the upload (sender); brief. */
    data object Starting : TransferState

    /** Receiver QR is up, waiting for the old device to connect. The transfer key is not carried here. */
    data class WaitingForPeer(val ip: String, val port: Int, val pin: String) : TransferState

    /**
     * Bytes are flowing. [progress] is in `[0, 1]`, or `-1f` for indeterminate. [totalBytes] is the
     * advisory size the reading is measured against; 0 means unknown.
     */
    data class Transferring(
        val role: Role,
        val progress: Float,
        val peerAlias: String? = null,
        val totalBytes: Long = 0L,
    ) : TransferState

    /** Receiver: the staging-to-promote critical section. */
    data object Promoting : TransferState

    /**
     * Sender: the last body byte has left and only the receiver's verdict is outstanding. The receiver
     * is verifying and importing, which can take minutes, so the bar has nothing left to show.
     */
    data object AwaitingVerdict : TransferState

    /**
     * Sender: the body is in, and the receiver's reported progress has stopped moving for longer than
     * a working import ever pauses. Not a failure — the receiver is still being asked — but the
     * screen stops promising completion and says the result is unconfirmed.
     */
    data object VerdictStalled : TransferState

    /**
     * Sender: `prepare-upload` is unanswered because the Desktop is waiting for its user to confirm an
     * account switch; the sender retries until it is answered.
     */
    data object AwaitingPeerConfirm : TransferState

    /** Receiver: verified and promoted on disk (ReadyForSignIn). Terminal success. */
    data object Promoted : TransferState

    /** Sender: the receiver's delayed 200 arrived, confirming the upload. Terminal success. */
    data object Sent : TransferState

    /** Terminal failure: a gate or guard reject, tampering, a network fault, or a pairing timeout. */
    data class Failed(val reason: String) : TransferState

    /** Terminal: cancelled by the user or by the other device. */
    data object Cancelled : TransferState

    /**
     * Sender: the wait for a verdict ended without one — the receiver never answered, stopped
     * answering, or forgot the session. Terminal for the service, but not a failure: the receiver
     * may well have finished, so this device keeps its data and does not offer to start over.
     */
    data object Unknown : TransferState

    /** RECEIVER is the new device, which imports; SENDER is the old device, which uploads. */
    enum class Role { RECEIVER, SENDER }
}

/** True once the transfer is terminal; the service tears down on the first terminal state. */
fun TransferState.isTerminal(): Boolean = when (this) {
    TransferState.Promoted, TransferState.Sent, is TransferState.Failed, TransferState.Cancelled,
    TransferState.Unknown -> true
    TransferState.Idle, TransferState.Starting, is TransferState.WaitingForPeer,
    is TransferState.Transferring, TransferState.Promoting, TransferState.AwaitingVerdict,
    TransferState.VerdictStalled, TransferState.AwaitingPeerConfirm -> false
}

/**
 * Receiver: whether a failure reported in this state may still write [TransferState.Failed].
 *
 * An already terminal transfer never changes. [TransferState.Promoting] now spans the import tail and
 * the promote, minutes on a large library, so a peer that goes away after the body is in changes
 * nothing — but the import's own verdict ([fromOutcome]: HMAC, decrypt, disk full, a failed P3 move)
 * always does, and must not be swallowed as a late link failure.
 */
fun TransferState.acceptsReceiverFailure(fromOutcome: Boolean): Boolean =
    !isTerminal() && (this != TransferState.Promoting || fromOutcome)

/** Sender: the body is on the wire and only the receiver's verdict is outstanding. */
fun TransferState.isAwaitingVerdict(): Boolean =
    this == TransferState.AwaitingVerdict || this == TransferState.VerdictStalled
