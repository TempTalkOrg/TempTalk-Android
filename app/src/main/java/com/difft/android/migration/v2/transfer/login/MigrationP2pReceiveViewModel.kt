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

/**
 * MVI ViewModel for the receive screen. The transfer runs on [MigrationTransferService]'s scope, not
 * here: this view model starts and cancels it through the service, and re-attaches on init by
 * collecting [MigrationTransferStateHolder] and mapping the role-agnostic [TransferState] into the
 * UI state. Because `StateFlow` replays its latest value, an Activity recreated mid-import re-binds
 * to the live transfer.
 *
 * The QR bitmap is re-rendered here from the holder's [QrPayload], so the process-singleton holder
 * stays free of Android types; the payload's transfer key is never rendered or logged. `onCleared`
 * does not cancel the transfer — the service owns it.
 */
class MigrationP2pReceiveViewModel(
    private val appContext: Context,
    private val account: String,
    // "Migrate from the old app on this device": loopback receiver, pairing payload handed over by
    // intent instead of a QR. False for the device-to-device flow.
    private val sameDevice: Boolean = false,
    // The container carries the old app's login session: this install has no account of its own and
    // comes up signed in, so the terminal state is a restart rather than a result code.
    private val credentialHandoff: Boolean = false,
    // "Transfer from computer": the Desktop shows the code and this device scans it, so the page opens
    // straight into the scanner instead of advertising a QR of its own.
    private val scanDesktop: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(beforeListening())
    val state: StateFlow<MigrationP2pReceiveState> = _state.asStateFlow()

    private val _effects = Channel<MigrationP2pReceiveEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /**
     * The pairing payload goes to the old app only from the screen that started the receiver — the
     * new-app-first path. When the receiver was started silently from the old app's own page, the
     * old app already holds the payload and is mid-send by the time this screen is created; handing
     * it over again would open a second confirmation page on top of its progress page.
     */
    private var ownsPairing = false

    /** The handoff fires once per listening session; a re-launch is the user's explicit choice. */
    private var legacySenderLaunched = false

    /** Liveness escape for the receipt wait; armed at most once. */
    private var receiptFallbackJob: Job? = null

    /** True for a moment after the listener rotated its code, so the page can say the code changed. */
    private val _codeRefreshed = MutableStateFlow(false)
    private var lastPin: String? = null
    private var codeRefreshedJob: Job? = null

    init {
        // viewModelScope collects on Main.immediate, so the holder's current value lands here before
        // start() runs. A terminal left behind by a finished session would be replayed as this screen's
        // own result and finish it at once, so drop it; a live transfer is left alone so re-entry
        // re-attaches to it. In handoff mode a Promoted holder is the result this screen exists to act
        // on, not a leftover, because the screen may be created only after the send began.
        val leftover = MigrationTransferStateHolder.state.value
        if (leftover.isTerminal() && !(credentialHandoff && leftover == TransferState.Promoted)) {
            MigrationTransferStateHolder.reset()
        }
        // Re-attach: map the live service-owned transfer state and QR payload into the UI state.
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
            // A dismissed scanner with nothing in flight is a step back to the source chooser.
            MigrationP2pReceiveIntent.ScanCancelled ->
                if (_state.value is MigrationP2pReceiveState.Starting || _state.value is MigrationP2pReceiveState.WaitingForPeer) leave()
            MigrationP2pReceiveIntent.Cancel -> MigrationTransferService.cancel(appContext)
            MigrationP2pReceiveIntent.Leave -> leave()
            MigrationP2pReceiveIntent.OpenUpdate -> _effects.trySend(MigrationP2pReceiveEffect.OpenUpdatePage)
        }
    }

    /**
     * A scanned Desktop export QR (`dir = "download"`) is carried by reverse pairing: this device
     * keeps its listener up and asks the computer to connect back and upload. Anything else — a plain
     * link code, a receive code from another phone, a code without a key — is refused here with a
     * hint, before the service is involved. The raw payload is never logged.
     */
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

    /**
     * Back to the source chooser. Anything still running is stopped first (a failed transfer has
     * already stopped itself; this is for the listening receiver on the QR page), then the
     * terminal Skipped state hands the Activity its result code.
     */
    private fun leave() {
        L.i { "[MigrationV2][P2P][Rx] leaving the receive flow" }
        MigrationTransferService.cancel(appContext)
        _state.update { MigrationP2pReceiveState.Skipped }
    }

    private fun start() {
        // Reset the holder synchronously here, before dispatching the async start intent: the init
        // collector replays the holder's latest value, so a terminal left by a previous transfer would
        // finish the Activity with that stale result before this one begins.
        //
        // Guarded, mirroring the send view model: an unconditional reset would wipe the holder while a
        // transfer is in flight, and the service's duplicate-start guard reads that same holder, so it
        // would report nothing running and launch a second receiver — two servers, two codes, two
        // writers on a single-writer holder. Reset only from Idle or a stale terminal.
        val holder = MigrationTransferStateHolder.state.value
        if (holder == TransferState.Idle || holder.isTerminal()) {
            MigrationTransferStateHolder.reset()
        } else {
            L.w { "[MigrationV2][P2P][Rx] receive start: transfer in flight, skipping pre-reset + start" }
            return
        }
        // Holder must reflect the chooser's account; the Activity passed it via the start intent.
        ownsPairing = true
        MigrationTransferService.startReceiver(appContext, account, sameDevice, credentialHandoff)
        // Desktop source: the receiver role is what the computer connects back to, so it comes up
        // regardless; the Desktop shows the code, this device scans it, and the scan starts reverse
        // pairing on that same listener. Its own code is never shown here (see mapToUiState).
        if (scanDesktop) _effects.trySend(MigrationP2pReceiveEffect.LaunchScanner)
    }

    /** A changed PIN on a live payload is a rotation; a payload appearing or vanishing is not. */
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

    /** Hand the pairing payload to the old app; the Activity owns the for-result launch. */
    private fun launchLegacySender(payload: QrPayload) {
        L.i { "[MigrationV2][P2P][Rx] same-device: handing the pairing payload to the old app" }
        _effects.trySend(MigrationP2pReceiveEffect.LaunchLegacySender(QrPayloadCodec.encodeText(payload)))
    }

    /** Promoting has no peer of its own; keep the name the Receiving state was already showing. */
    private fun keepPeer(next: MigrationP2pReceiveState, current: MigrationP2pReceiveState): MigrationP2pReceiveState =
        if (next is MigrationP2pReceiveState.Receiving && next.peerAlias == null &&
            current is MigrationP2pReceiveState.Receiving
        ) {
            next.copy(peerAlias = current.peerAlias)
        } else {
            next
        }

    /**
     * What the page shows before the listener is up. The device-to-device flow shows the code page
     * with its code still to come, so the ~100 ms the server takes to bind does not flash a page of
     * its own. Same device and the Desktop scan keep "preparing": neither shows a code of ours.
     */
    private fun beforeListening(): MigrationP2pReceiveState =
        if (sameDevice || scanDesktop) MigrationP2pReceiveState.Starting else MigrationP2pReceiveState.WaitingForPeer(qrBitmap = null)

    /**
     * Map the role-agnostic transfer state into the receiver UI state. The QR bitmap is re-rendered
     * here from [qr] so the holder stays free of Android types and re-attach survives Activity death.
     */
    private fun mapToUiState(transfer: TransferState, qr: QrPayload?, receiptDelivered: Boolean, refreshed: Boolean): MigrationP2pReceiveState =
        when (transfer) {
            TransferState.Idle, TransferState.Starting -> beforeListening()
            // Desktop source: the listener is only the carrier of the coming pull; the scanner is on top
            // and there is no code of ours to show, so the page stays on "preparing".
            is TransferState.WaitingForPeer -> if (scanDesktop) {
                MigrationP2pReceiveState.Starting
            } else {
                // The address and pairing pin stay in the engine state, which the QR payload and the
                // handshake need; the screen renders only the QR, so the UI state carries only that.
                MigrationP2pReceiveState.WaitingForPeer(
                    // Same device: nothing to scan, the payload travels by intent.
                    qrBitmap = if (sameDevice) null else qr?.let { QrPayloadCodec.encode(it) },
                    sameDevice = sameDevice,
                    refreshed = refreshed,
                )
            }
            is TransferState.AwaitingDesktopConfirm ->
                MigrationP2pReceiveState.AwaitingDesktopConfirm(transfer.peerAlias)
            is TransferState.AwaitingDesktopConnect ->
                MigrationP2pReceiveState.AwaitingDesktopConnect(transfer.peerAlias)
            // Carry the byte progress while transferring; the staging-to-promote phase has none, since its
            // size is already on disk, so it falls back to indeterminate.
            is TransferState.Transferring -> MigrationP2pReceiveState.Receiving(transfer.progress, transfer.peerAlias, transfer.totalBytes)
            // Everything is in; the container is being checked and written. No byte progress applies.
            TransferState.Promoting -> MigrationP2pReceiveState.Verifying
            TransferState.Promoted -> when {
                !credentialHandoff -> MigrationP2pReceiveState.Promoted
                // The sender reads the 200 as its licence to sign itself out, so restarting this
                // process before the bytes are on the wire could cut the connection first.
                receiptDelivered -> MigrationP2pReceiveState.Activated
                else -> {
                    armReceiptFallback()
                    MigrationP2pReceiveState.Verifying
                }
            }
            // Gate, guard and tamper rejects carry the typed reason; the screen maps the wire token to the
            // right string and falls back to a generic one for other errors.
            is TransferState.Failed -> {
                L.w { "[MigrationV2][P2P][Rx] receive failed reason=${transfer.reason}" }
                MigrationP2pReceiveState.Error(transfer.reason)
            }
            // Committed, tail unconfirmed: never Promoted, so no in-process sign-in is started — the
            // Activity cold-starts instead and the promote finishes there.
            TransferState.CommittedUnconfirmed -> MigrationP2pReceiveState.CommittedUnconfirmed
            TransferState.Cancelled -> MigrationP2pReceiveState.Skipped
            // Sender-only terminal — not reachable on the receive screen; treat as a benign no-op.
            TransferState.Sent, TransferState.AwaitingVerdict, TransferState.VerdictStalled, TransferState.Unknown,
            TransferState.AwaitingPeerConfirm -> _state.value
        }

    /**
     * Deliberate liveness escape: waiting for the receipt for ever would strand a user whose sender
     * died between the promote and the flush. A missed receipt degrades to the old app's in-session
     * reconciliation, which converges without a cold start, so advancing is the safer failure.
     */
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
        /** How long the restart waits for the 200 to be written + flushed before going anyway. */
        const val RECEIPT_FLUSH_TIMEOUT_MS = 3_000L
    }
}

/** How long the pairing page says the code was refreshed after a rotation. */
private const val CODE_REFRESHED_HINT_MS = 2_500L
