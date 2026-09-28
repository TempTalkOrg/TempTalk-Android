package com.difft.android.migration.v2.transfer

import com.difft.android.p2p.engine.model.QrPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-singleton state holder for the migration transfer. The live state lives here rather than on
 * the Activity, so an Activity destroyed mid-import and later recreated re-collects the same [state]
 * and [qrPayload] and re-binds to the in-flight transfer.
 *
 * Single-writer discipline: only [MigrationTransferService] calls the writer methods, the view models
 * are read-only collectors. No Android UI types are held here; the QR is exposed as a [QrPayload] the
 * view model re-renders. `QrPayload` carries the ephemeral transfer key, which is never rendered or
 * logged.
 */
object MigrationTransferStateHolder {

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val _qrPayload = MutableStateFlow<QrPayload?>(null)
    val qrPayload: StateFlow<QrPayload?> = _qrPayload.asStateFlow()

    private val _receiptDelivered = MutableStateFlow(false)

    /**
     * Receiver role: the HTTP 200 verdict has been written to the socket and flushed. The same-device
     * handoff waits for this before restarting the app, because the sender reads that 200 as its
     * licence to sign out and restarting first could cut the connection before the bytes leave.
     */
    val receiptDelivered: StateFlow<Boolean> = _receiptDelivered.asStateFlow()

    /** Atomically sets the transfer state. Called only by [MigrationTransferService]. */
    fun update(next: TransferState) {
        _state.update { next }
    }

    /** Publishes the QR payload for the view model to re-render. Called only by the service. */
    fun publishQr(payload: QrPayload) {
        _qrPayload.update { payload }
    }

    /** Marks the 200 as written and flushed. Called only by [MigrationTransferService]. */
    fun markReceiptDelivered() {
        _receiptDelivered.update { true }
    }

    private val _pairingClaimed = MutableStateFlow(false)

    /** A scanner has read the current code and claimed its PIN: the code stops rotating. */
    val pairingClaimed: StateFlow<Boolean> = _pairingClaimed.asStateFlow()

    fun markPairingClaimed() {
        _pairingClaimed.update { true }
    }

    private val _notices = MutableSharedFlow<TransferNotice>(extraBufferCapacity = 4)

    /** One-off things the service has to tell whoever is watching, that are not a state. */
    val notices: SharedFlow<TransferNotice> = _notices.asSharedFlow()

    fun notify(notice: TransferNotice) {
        _notices.tryEmit(notice)
    }

    private val _pairingPageVisible = MutableStateFlow(false)

    /** The pairing page is on screen; the receiver's no-peer clock runs only while it is not. */
    val pairingPageVisible: StateFlow<Boolean> = _pairingPageVisible.asStateFlow()

    fun setPairingPageVisible(visible: Boolean) {
        _pairingPageVisible.update { visible }
    }

    /** Resets to a clean pre-transfer state. Called by [MigrationTransferService] on a fresh start. */
    fun reset() {
        _state.update { TransferState.Idle }
        _qrPayload.update { null }
        _receiptDelivered.update { false }
        _pairingClaimed.update { false }
    }
}

/** Events from the service that change no transfer state but that the user should hear. */
enum class TransferNotice {
    /** The user asked to cancel while the receiver was already past its commit point: the import goes on. */
    CANCEL_TOO_LATE,
}
