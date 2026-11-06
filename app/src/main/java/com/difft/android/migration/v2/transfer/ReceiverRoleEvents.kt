package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.android.server.ReceiverHttpServer
import kotlinx.coroutines.CoroutineScope

/**
 * The receiver role's server events, progress and failures, folded into
 * [MigrationTransferStateHolder]. One per receiver role, owned by [MigrationTransferService], which
 * keeps the role's lifecycle; [onProgressPercent] drives the ongoing notification.
 */
internal class ReceiverRoleEvents(private val onProgressPercent: (Int) -> Unit) {

    /** The sender's advisory total for the receive in flight; carried on every progress update for the reading. */
    private var totalBytes = 0L

    /** Decides what a receive that stopped short is reported as; one per receiver role. */
    private var arbiter: ReceiverFailureArbiter? = null

    fun attach(scope: CoroutineScope) {
        arbiter = ReceiverFailureArbiter(scope, ::fail)
    }

    fun detach() {
        arbiter?.clear()
        arbiter = null
    }

    /** Routes a receiver failure through the arbiter, so a dropped link can still turn out to be the peer's cancel. */
    fun failEventually(reason: String) {
        arbiter?.onFailure(reason) ?: fail(reason)
    }

    /**
     * The receiver's terminal Failed — unless the transfer already ended, or is committing: a peer that
     * goes away after the body is in changes nothing about the data already received, and a late
     * failure must not overwrite the outcome the person is looking at. The import's own verdict
     * ([fromOutcome]) always lands, because Promoting now spans the import tail and the promote.
     */
    fun fail(reason: String, fromOutcome: Boolean = false) {
        val current = MigrationTransferStateHolder.state.value
        if (!current.acceptsReceiverFailure(fromOutcome)) {
            L.w { "[MigrationV2][P2P][FGS] receiver failure after ${current::class.simpleName} ignored: $reason" }
            return
        }
        MigrationTransferStateHolder.update(TransferState.Failed(reason))
    }

    /**
     * Approximate receiver progress, body bytes against the sender's advisory estimate, driving the
     * determinate bar on the receive screen and the notification. Guarded so a late callback cannot
     * clobber a state that has already left Transferring.
     */
    fun onProgress(ratio: Float) {
        val current = MigrationTransferStateHolder.state.value
        if (current is TransferState.Transferring && current.role == TransferState.Role.RECEIVER) {
            MigrationTransferStateHolder.update(
                TransferState.Transferring(TransferState.Role.RECEIVER, ratio, current.peerAlias, totalBytes),
            )
            if (ratio > 0f) {
                onProgressPercent((ratio * 100).toInt())
            }
        }
    }

    fun onServerEvent(event: ReceiverHttpServer.ServerEvent) {
        when (event) {
            is ReceiverHttpServer.ServerEvent.IncomingPrepared -> {
                val peer = event.peer.alias.takeIf { it.isNotBlank() }
                totalBytes = event.totalBytes
                arbiter?.onPrepared()
                L.i { "[MigrationV2][P2P][FGS] state → Transferring (receiver) peer=$peer totalBytes=${event.totalBytes}" }
                MigrationTransferStateHolder.update(
                    TransferState.Transferring(TransferState.Role.RECEIVER, INDETERMINATE, peerAlias = peer, totalBytes = event.totalBytes),
                )
            }
            // A gate reject (env/account/version) arrives as ServerEvent.Error carrying the typed wire
            // reason; map it to a terminal Failed so the view model can show the specific message.
            is ReceiverHttpServer.ServerEvent.Error -> {
                val reason = receiverFailureReason(event.detail, event.cause)
                L.w { "[MigrationV2][P2P][FGS] server event error=${event.detail} reason=$reason" }
                failEventually(reason)
            }
            is ReceiverHttpServer.ServerEvent.UploadStarted -> arbiter?.onUploadStarted()
            is ReceiverHttpServer.ServerEvent.PeerCancelled -> {
                L.i { "[MigrationV2][P2P][FGS] peer cancelled its session" }
                arbiter?.onPeerCancelled() ?: fail(ReceiverFailureArbiter.PEER_LEFT)
            }
            // The 200 is written AND flushed before this event fires, so it is the signal that the
            // sender can now read its receipt. The handoff receive screen waits for it before
            // restarting the process.
            is ReceiverHttpServer.ServerEvent.FileReceived -> MigrationTransferStateHolder.markReceiptDelivered()
            is ReceiverHttpServer.ServerEvent.Claimed -> {
                L.i { "[MigrationV2][P2P][FGS] pairing code claimed by ${event.peer.alias}" }
                MigrationTransferStateHolder.markPairingClaimed()
            }
            else -> { /* Started/PeerRegistered/Stopped — not surfaced here */ }
        }
    }

    private companion object {
        /** Indeterminate progress signal carried in [TransferState.Transferring]. */
        const val INDETERMINATE = -1f
    }
}
