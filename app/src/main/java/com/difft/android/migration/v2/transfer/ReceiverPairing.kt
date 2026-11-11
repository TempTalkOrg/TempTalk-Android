package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveController
import com.difft.android.p2p.android.qr.QrPayloadCodec
import com.difft.android.p2p.engine.model.QrPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull










internal class ReceiverPairing(
    private val pairingTimeoutMs: Long,
    private val rotateIntervalMs: Long,
) {
    



    private val scanRequests = MutableStateFlow<QrPayload?>(null)

    
    fun resetScan() {
        scanRequests.value = null
    }

    





    fun requestScan(raw: String) {
        val target = QrPayloadCodec.decode(raw)
        if (target?.transferKey == null) {
            L.w { "[MigrationV2][P2P][FGS] reverse-pair ignored — not a migration payload" }
            return
        }
        val current = MigrationTransferStateHolder.state.value
        if (current !is TransferState.WaitingForPeer && current != TransferState.Starting) {
            L.w { "[MigrationV2][P2P][FGS] reverse-pair with no receiver (state=$current) → failing" }
            MigrationTransferStateHolder.update(TransferState.Failed(MigrationTransferService.REASON_NO_RECEIVER))
            return
        }
        L.i { "[MigrationV2][P2P][FGS] reverse-pair requested alias=${target.alias} ip=${target.ip}:${target.port}" }
        scanRequests.value = target
    }
    



    suspend fun awaitPeerOrScan(): QrPayload? {
        
        
        
        val peerOrScan = merge(
            MigrationTransferStateHolder.state
                .filter { it.isTerminal() || it is TransferState.Transferring || it is TransferState.Promoting || it is TransferState.AwaitingVerdict }
                .map<TransferState, PairingWait> { PairingWait.Peer },
            scanRequests.filterNotNull().map { PairingWait.Scan(it) },
        )
        return when (val outcome = awaitPeerWhileVisibleOrTimeout(peerOrScan)) {
            null -> {
                
                if (!MigrationTransferStateHolder.state.value.isTerminal()) {
                    L.w { "[MigrationV2][P2P][FGS] pairing timeout (no peer) — teardown" }
                    MigrationTransferStateHolder.update(TransferState.Failed(MigrationTransferService.REASON_PAIRING_TIMEOUT))
                }
                null
            }
            is PairingWait.Scan -> outcome.target
            else -> {
                
                MigrationTransferStateHolder.state.first { it.isTerminal() }
                null
            }
        }
    }

    
    suspend fun rotatePin(controller: MigrationP2pReceiveController) {
        while (true) {
            delay(rotateIntervalMs)
            if (MigrationTransferStateHolder.state.value !is TransferState.WaitingForPeer) return
            if (MigrationTransferStateHolder.pairingClaimed.value) return
            val next = controller.rotatePin() ?: return
            MigrationTransferStateHolder.publishQr(next)
            MigrationTransferStateHolder.update(TransferState.WaitingForPeer(next.ip, next.port, next.pin))
            L.i { "[MigrationV2][P2P][FGS] pairing code rotated" }
        }
    }

    
    private suspend fun awaitPeerWhileVisibleOrTimeout(peerOrScan: Flow<PairingWait>): PairingWait? {
        val visible = MigrationTransferStateHolder.pairingPageVisible
        while (true) {
            val result = if (visible.value) {
                merge(peerOrScan, visible.filter { !it }.map<Boolean, PairingWait> { PairingWait.Visibility }).first()
            } else {
                withTimeoutOrNull(pairingTimeoutMs) {
                    merge(peerOrScan, visible.filter { it }.map<Boolean, PairingWait> { PairingWait.Visibility }).first()
                }
            }
            if (result != PairingWait.Visibility) return result
        }
    }

    private sealed interface PairingWait {
        data object Peer : PairingWait
        data object Visibility : PairingWait
        data class Scan(val target: QrPayload) : PairingWait
    }
}
