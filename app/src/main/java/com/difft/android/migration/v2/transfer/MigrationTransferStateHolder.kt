package com.difft.android.migration.v2.transfer

import com.difft.android.p2p.engine.model.QrPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update











object MigrationTransferStateHolder {

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val _qrPayload = MutableStateFlow<QrPayload?>(null)
    val qrPayload: StateFlow<QrPayload?> = _qrPayload.asStateFlow()

    private val _receiptDelivered = MutableStateFlow(false)

    




    val receiptDelivered: StateFlow<Boolean> = _receiptDelivered.asStateFlow()

    
    fun update(next: TransferState) {
        _state.update { next }
    }

    
    fun publishQr(payload: QrPayload) {
        _qrPayload.update { payload }
    }

    
    fun markReceiptDelivered() {
        _receiptDelivered.update { true }
    }

    private val _pairingClaimed = MutableStateFlow(false)

    
    val pairingClaimed: StateFlow<Boolean> = _pairingClaimed.asStateFlow()

    fun markPairingClaimed() {
        _pairingClaimed.update { true }
    }

    private val _notices = MutableSharedFlow<TransferNotice>(extraBufferCapacity = 4)

    
    val notices: SharedFlow<TransferNotice> = _notices.asSharedFlow()

    fun notify(notice: TransferNotice) {
        _notices.tryEmit(notice)
    }

    private val _pairingPageVisible = MutableStateFlow(false)

    
    val pairingPageVisible: StateFlow<Boolean> = _pairingPageVisible.asStateFlow()

    fun setPairingPageVisible(visible: Boolean) {
        _pairingPageVisible.update { visible }
    }

    
    fun reset() {
        _state.update { TransferState.Idle }
        _qrPayload.update { null }
        _receiptDelivered.update { false }
        _pairingClaimed.update { false }
    }
}


enum class TransferNotice {
    
    CANCEL_TOO_LATE,
}
