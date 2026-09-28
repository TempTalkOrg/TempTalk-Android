package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveController
import com.difft.android.p2p.engine.model.QrPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The receiver role's pairing phase, read off [MigrationTransferStateHolder]: the wait for a peer or
 * a Desktop pull request with its no-peer clock, and the code rotation beside it.
 *
 * The clock runs only while the pairing page is off screen; once the page is gone, a listener nobody
 * can scan is torn down after [pairingTimeoutMs]. Every [rotateIntervalMs] the listener issues a
 * fresh PIN so a photographed code stops working at once, until a scanner claims the code, a peer
 * connects, or the listener goes.
 */
internal class ReceiverPairing(
    private val pullRequests: Flow<QrPayload>,
    private val pairingTimeoutMs: Long,
    private val rotateIntervalMs: Long,
) {
    /**
     * Suspends until the receive resolves: returns the Desktop to pull from when a pull request ended
     * the wait, null once the holder is terminal (including the pairing timeout, written here).
     */
    suspend fun awaitPeerOrPull(): QrPayload? {
        // Leave the wait only on states that mean the peer connected: terminal, Transferring or
        // Promoting. Idle and Starting do not lift the cap, so the no-peer window stays bounded. A pull
        // request also ends the wait — it is the peer, reached the other way round.
        val peerOrPull = merge(
            MigrationTransferStateHolder.state
                .filter { it.isTerminal() || it is TransferState.Transferring || it is TransferState.Promoting || it is TransferState.AwaitingVerdict }
                .map<TransferState, PairingWait> { PairingWait.Peer },
            pullRequests.map<QrPayload, PairingWait> { PairingWait.Pull(it) },
        )
        return when (val outcome = awaitPeerWhileVisibleOrTimeout(peerOrPull)) {
            null -> {
                // Timed out while still waiting for a peer to connect.
                if (!MigrationTransferStateHolder.state.value.isTerminal()) {
                    L.w { "[MigrationV2][P2P][FGS] pairing timeout (no peer) — teardown" }
                    MigrationTransferStateHolder.update(TransferState.Failed(MigrationTransferService.REASON_PAIRING_TIMEOUT))
                }
                null
            }
            is PairingWait.Pull -> outcome.target
            else -> {
                // Peer connected (or already terminal): await the real terminal outcome with no timeout.
                MigrationTransferStateHolder.state.first { it.isTerminal() }
                null
            }
        }
    }

    /** Rotates the live listener's code until a scanner claims it, a peer connects, or the listener goes. */
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

    /** Null on timeout; otherwise what ended the wait. Visibility changes only restart the clock. */
    private suspend fun awaitPeerWhileVisibleOrTimeout(peerOrPull: Flow<PairingWait>): PairingWait? {
        val visible = MigrationTransferStateHolder.pairingPageVisible
        while (true) {
            val result = if (visible.value) {
                merge(peerOrPull, visible.filter { !it }.map<Boolean, PairingWait> { PairingWait.Visibility }).first()
            } else {
                withTimeoutOrNull(pairingTimeoutMs) {
                    merge(peerOrPull, visible.filter { it }.map<Boolean, PairingWait> { PairingWait.Visibility }).first()
                }
            }
            if (result != PairingWait.Visibility) return result
        }
    }

    private sealed interface PairingWait {
        data object Peer : PairingWait
        data object Visibility : PairingWait
        data class Pull(val target: QrPayload) : PairingWait
    }
}
