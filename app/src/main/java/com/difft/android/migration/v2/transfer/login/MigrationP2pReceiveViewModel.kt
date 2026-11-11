package com.difft.android.migration.v2.transfer.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.v2.transfer.MigrationTransferService
import com.difft.android.p2p.engine.model.TransferDirection
import com.difft.android.p2p.engine.model.direction
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.TransferState
import com.difft.android.migration.v2.transfer.isTerminal
import com.difft.android.p2p.android.qr.QrPayloadCodec
import com.difft.android.p2p.engine.model.QrPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay












class MigrationP2pReceiveViewModel(
    private val appContext: Context,
    private val account: String,
    
    
    private val sameDevice: Boolean = false,
    
    
    private val credentialHandoff: Boolean = false,
    
    
    private val scanDesktop: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(beforeListening())
    val state: StateFlow<MigrationP2pReceiveState> = _state.asStateFlow()

    private val _effects = Channel<MigrationP2pReceiveEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    





    private var ownsPairing = false

    
    private var legacySenderLaunched = false

    
    private var receiptFallbackJob: Job? = null

    
    private val _codeRefreshed = MutableStateFlow(false)
    private var lastPin: String? = null
    private var codeRefreshedJob: Job? = null

    init {
        
        
        
        
        
        val leftover = MigrationTransferStateHolder.state.value
        if (leftover.isTerminal() && !(credentialHandoff && leftover == TransferState.Promoted)) {
            MigrationTransferStateHolder.reset()
        }
        
        viewModelScope.launch {
            MigrationTransferStateHolder.state
                .combine(MigrationTransferStateHolder.qrPayload) { transfer, qr -> transfer to qr }
                .combine(MigrationTransferStateHolder.receiptDelivered) { (transfer, qr), receipt ->
                    Triple(transfer, qr, receipt)
                }
                .combine(_codeRefreshed) { (transfer, qr, receipt), refreshed -> Snapshot(transfer, qr, receipt, refreshed) }
                .collect { (transfer, qr, receipt, refreshed) ->
                    noteCodeRotation(qr)
                    _state.update { current -> keepPeer(mapToUiState(transfer, qr, receipt, refreshed), current) }
                    if (sameDevice && ownsPairing && transfer is TransferState.WaitingForPeer && qr != null && !legacySenderLaunched) {
                        legacySenderLaunched = true
                        launchLegacySender(qr)
                    }
                }
        }
    }

    fun onIntent(intent: MigrationP2pReceiveIntent) {
        when (intent) {
            MigrationP2pReceiveIntent.Start -> start()
            MigrationP2pReceiveIntent.Retry -> start()
            MigrationP2pReceiveIntent.RelaunchLegacySender -> MigrationTransferStateHolder.qrPayload.value?.let(::launchLegacySender)
            MigrationP2pReceiveIntent.ScanPeerQr -> _effects.trySend(MigrationP2pReceiveEffect.LaunchScanner)
            is MigrationP2pReceiveIntent.PeerQrScanned -> onPeerQrScanned(intent.raw)
            
            MigrationP2pReceiveIntent.ScanCancelled ->
                if (_state.value is MigrationP2pReceiveState.Starting || _state.value is MigrationP2pReceiveState.WaitingForPeer) leave()
            MigrationP2pReceiveIntent.Cancel -> MigrationTransferService.cancel(appContext)
            MigrationP2pReceiveIntent.Leave -> leave()
            MigrationP2pReceiveIntent.OpenUpdate -> _effects.trySend(MigrationP2pReceiveEffect.OpenUpdatePage)
        }
    }

    





    private fun onPeerQrScanned(raw: String) {
        val payload = QrPayloadCodec.decode(raw)
        if (payload?.transferKey == null || payload.direction != TransferDirection.DOWNLOAD) {
            L.w { "[MigrationV2][P2P][Rx] scanned code is not a Desktop export code (dir=${payload?.dir})" }
            _effects.trySend(MigrationP2pReceiveEffect.Toast(appContext.getString(R.string.migration_p2p_receive_scan_not_export_code)))
            return
        }
        L.i { "[MigrationV2][P2P][Rx] Desktop export code scanned → reverse pairing" }
        MigrationTransferService.startReversePair(appContext, raw)
    }

    




    private fun leave() {
        L.i { "[MigrationV2][P2P][Rx] leaving the receive flow" }
        MigrationTransferService.cancel(appContext)
        _state.update { MigrationP2pReceiveState.Skipped }
    }

    private fun start() {
        
        
        
        
        
        
        
        
        val holder = MigrationTransferStateHolder.state.value
        if (holder == TransferState.Idle || holder.isTerminal()) {
            MigrationTransferStateHolder.reset()
        } else {
            L.w { "[MigrationV2][P2P][Rx] receive start: transfer in flight, skipping pre-reset + start" }
            return
        }
        
        ownsPairing = true
        MigrationTransferService.startReceiver(appContext, account, sameDevice, credentialHandoff)
        
        
        
        if (scanDesktop) _effects.trySend(MigrationP2pReceiveEffect.LaunchScanner)
    }

    
    private fun noteCodeRotation(qr: QrPayload?) {
        val pin = qr?.pin
        if (pin != null && lastPin != null && pin != lastPin) {
            _codeRefreshed.value = true
            codeRefreshedJob?.cancel()
            codeRefreshedJob = viewModelScope.launch {
                delay(CODE_REFRESHED_HINT_MS)
                _codeRefreshed.value = false
            }
        }
        lastPin = pin
    }

    private data class Snapshot(val transfer: TransferState, val qr: QrPayload?, val receipt: Boolean, val refreshed: Boolean)

    
    private fun launchLegacySender(payload: QrPayload) {
        L.i { "[MigrationV2][P2P][Rx] same-device: handing the pairing payload to the old app" }
        _effects.trySend(MigrationP2pReceiveEffect.LaunchLegacySender(QrPayloadCodec.encodeText(payload)))
    }

    
    private fun keepPeer(next: MigrationP2pReceiveState, current: MigrationP2pReceiveState): MigrationP2pReceiveState =
        if (next is MigrationP2pReceiveState.Receiving && next.peerAlias == null &&
            current is MigrationP2pReceiveState.Receiving
        ) {
            next.copy(peerAlias = current.peerAlias)
        } else {
            next
        }

    




    private fun beforeListening(): MigrationP2pReceiveState =
        if (sameDevice || scanDesktop) MigrationP2pReceiveState.Starting else MigrationP2pReceiveState.WaitingForPeer(qrBitmap = null)

    



    private fun mapToUiState(transfer: TransferState, qr: QrPayload?, receiptDelivered: Boolean, refreshed: Boolean): MigrationP2pReceiveState =
        when (transfer) {
            TransferState.Idle, TransferState.Starting -> beforeListening()
            
            
            is TransferState.WaitingForPeer -> if (scanDesktop) {
                MigrationP2pReceiveState.Starting
            } else {
                
                
                MigrationP2pReceiveState.WaitingForPeer(
                    
                    qrBitmap = if (sameDevice) null else qr?.let { QrPayloadCodec.encode(it) },
                    sameDevice = sameDevice,
                    refreshed = refreshed,
                )
            }
            is TransferState.AwaitingDesktopConfirm ->
                MigrationP2pReceiveState.AwaitingDesktopConfirm(transfer.peerAlias)
            is TransferState.AwaitingDesktopConnect ->
                MigrationP2pReceiveState.AwaitingDesktopConnect(transfer.peerAlias)
            
            
            is TransferState.Transferring -> MigrationP2pReceiveState.Receiving(transfer.progress, transfer.peerAlias, transfer.totalBytes)
            
            TransferState.Promoting -> MigrationP2pReceiveState.Verifying
            TransferState.Promoted -> when {
                !credentialHandoff -> MigrationP2pReceiveState.Promoted
                
                
                receiptDelivered -> MigrationP2pReceiveState.Activated
                else -> {
                    armReceiptFallback()
                    MigrationP2pReceiveState.Verifying
                }
            }
            
            
            is TransferState.Failed -> {
                L.w { "[MigrationV2][P2P][Rx] receive failed reason=${transfer.reason}" }
                MigrationP2pReceiveState.Error(transfer.reason)
            }
            
            
            TransferState.CommittedUnconfirmed -> MigrationP2pReceiveState.CommittedUnconfirmed
            TransferState.Cancelled -> MigrationP2pReceiveState.Skipped
            
            TransferState.Sent, TransferState.AwaitingVerdict, TransferState.VerdictStalled, TransferState.Unknown,
            TransferState.AwaitingPeerConfirm -> _state.value
        }

    




    private fun armReceiptFallback() {
        if (receiptFallbackJob?.isActive == true) return
        receiptFallbackJob = viewModelScope.launch {
            delay(RECEIPT_FLUSH_TIMEOUT_MS)
            L.w { "[MigrationV2][Handoff] receipt not observed within ${RECEIPT_FLUSH_TIMEOUT_MS}ms — activating anyway" }
            _state.update { current ->
                if (current is MigrationP2pReceiveState.Activated) current else MigrationP2pReceiveState.Activated
            }
        }
    }

    private companion object {
        
        const val RECEIPT_FLUSH_TIMEOUT_MS = 3_000L
    }
}


private const val CODE_REFRESHED_HINT_MS = 2_500L
