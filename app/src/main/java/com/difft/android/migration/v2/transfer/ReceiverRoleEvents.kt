package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.android.server.ReceiverHttpServer
import kotlinx.coroutines.CoroutineScope






internal class ReceiverRoleEvents(private val onProgressPercent: (Int) -> Unit) {

    
    private var totalBytes = 0L

    
    private var arbiter: ReceiverFailureArbiter? = null

    fun attach(scope: CoroutineScope) {
        arbiter = ReceiverFailureArbiter(scope, ::fail)
    }

    fun detach() {
        arbiter?.clear()
        arbiter = null
    }

    
    fun failEventually(reason: String) {
        arbiter?.onFailure(reason) ?: fail(reason)
    }

    





    fun fail(reason: String, fromOutcome: Boolean = false) {
        val current = MigrationTransferStateHolder.state.value
        if (!current.acceptsReceiverFailure(fromOutcome)) {
            L.w { "[MigrationV2][P2P][FGS] receiver failure after ${current::class.simpleName} ignored: $reason" }
            return
        }
        MigrationTransferStateHolder.update(TransferState.Failed(reason))
    }

    




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
            
            
            
            is ReceiverHttpServer.ServerEvent.FileReceived -> MigrationTransferStateHolder.markReceiptDelivered()
            is ReceiverHttpServer.ServerEvent.Claimed -> {
                L.i { "[MigrationV2][P2P][FGS] pairing code claimed by ${event.peer.alias}" }
                MigrationTransferStateHolder.markPairingClaimed()
            }
            else -> {  }
        }
    }

    private companion object {
        
        const val INDETERMINATE = -1f
    }
}
