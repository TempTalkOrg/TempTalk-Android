package com.difft.android.migration.v2.transfer.send

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.migration.v2.export.ExportEstimate
import com.difft.android.migration.v2.handoff.HandoffAuthPolicy
import com.difft.android.migration.v2.handoff.HandoffSpaceCheck
import com.difft.android.migration.v2.handoff.MigrationHandoffReconciler
import com.difft.android.migration.v2.transfer.MigrationTransferService
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.PendingVerdict
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.SenderVerdictTracker
import com.difft.android.migration.v2.transfer.TransferNotice
import com.difft.android.migration.v2.transfer.TransferState
import com.difft.android.migration.v2.transfer.Verdict
import com.difft.android.migration.v2.transfer.isTerminal
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.android.qr.QrPayloadCodec
import com.difft.android.p2p.engine.model.QrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI ViewModel for the send screen, the mirror of
 * [com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveViewModel] in the sender role.
 *
 * The send runs on [MigrationTransferService]'s scope, not here: this view model starts and cancels
 * it through the service, and re-attaches on init by collecting [MigrationTransferStateHolder] and
 * mapping the role-agnostic [TransferState] into the UI state, so an Activity recreated mid-send
 * re-binds to the in-flight transfer. The scanned QR is kept locally and only that raw string is
 * passed to the service; the account, environment and transfer key are read live inside the
 * controller and never marshalled through an Intent extra or logged. `onCleared` does not cancel the
 * transfer — the service owns it.
 */
class MigrationP2pSendViewModel(
    private val appContext: Context,
    // Sizes shown on the pairing page; null (tests, demo) shows no estimate. Runs off the main thread.
    private val estimator: (suspend () -> ExportEstimate)? = null,
    // The decision was already made on the page that opened this one (the Desktop-login confirmation):
    // start as soon as the payload is in, with this attachment choice, instead of showing Ready.
    private val autoStart: Boolean = false,
    private val initialIncludeAttachments: Boolean = true,
    // Same-device no-login credential handoff: this send carries the login session itself, so it is
    // gated on a user-presence check and its failure screen is gated on the peer's answer.
    private val credentialHandoff: Boolean = false,
    // Whether this install may still hand its session over, re-checked here because it is the last
    // point before the user commits: null = allowed, otherwise the wire token to show.
    private val handoffBlockReason: (() -> String?)? = null,
    // Which user-presence check applies on this device (App Lock / device credential / none).
    private val authMethod: (() -> HandoffAuthPolicy.Method)? = null,
    // Free-space verdict for (estimate, includeAttachments). Same-device only — both apps share one
    // volume there, so the sender can answer for the receiver.
    private val spaceVerdict: ((ExportEstimate, Boolean) -> HandoffSpaceCheck.Verdict)? = null,
    // Ask the new app, in-session, whether it nonetheless finished. Gates the restart affordance.
    private val reconcile: (suspend () -> MigrationHandoffReconciler.Outcome)? = null,
    // Desktop link, account switch: the Desktop holds `prepare-upload` until its user confirms, so
    // the sender must retry through the wait instead of failing on the first timeout.
    private val awaitPeerConfirm: Boolean = false,
    // Settles which of a code's advertised addresses answers (TCP + TLS + pin race); null (tests)
    // keeps the code's first address. Runs only when the code carries more than one.
    private val resolveEndpoint: (suspend (QrPayload) -> QrPayload?)? = null,
    // Tells the receiver its code was read; null (tests) means the confirmation page opens at once.
    private val claimPairing: (suspend (QrPayload) -> SenderHttpClient.PairingClaim)? = null,
    // The send whose verdict this device never learned, if any; null (tests) means none is ever pending.
    private val loadPendingVerdict: (suspend () -> PendingVerdict?)? = null,
    private val clearPendingVerdict: (suspend () -> Unit)? = null,
    // One `/status` question to the receiver of a pending send.
    private val probeVerdict: (suspend (SenderSessionRef) -> Verdict)? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<MigrationP2pSendState>(MigrationP2pSendState.Idle)
    val state: StateFlow<MigrationP2pSendState> = _state.asStateFlow()

    /** The paired device's name, kept beyond Ready so the in-flight and done screens can still show it. */
    private val _targetAlias = MutableStateFlow<String?>(null)
    val targetAlias: StateFlow<String?> = _targetAlias.asStateFlow()

    /** True when the receiver is the new app on this device, paired over loopback. */
    private val _sameDevice = MutableStateFlow(false)
    val sameDevice: StateFlow<Boolean> = _sameDevice.asStateFlow()

    private val _effects = Channel<MigrationP2pSendEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Raw scanned QR, passed straight to the service and never decoded into identity here. */
    private var scannedRaw: String? = null

    /** The scanned peer's name, kept across the auth step so Ready can still name the target. */
    private var pendingAlias: String? = null

    /** At most one reconciliation in flight; a second trigger while it runs is a no-op. */
    private var reconcileJob: Job? = null

    /** At most one verdict re-check in flight. */
    private var recheckJob: Job? = null

    /** Handoff: the confirmation page to return to after the user-presence prompt. */
    private var readyBeforeAuth: MigrationP2pSendState.Ready? = null

    /** Handoff: user presence has been proven once in this session. */
    private var authConfirmed = false

    /** The size the progress reading is measured against; 0 until the estimate lands. */
    private var estimatedTotalBytes = 0L

    /** True once this screen has asked the service to send; a screen left before that owes the peer a cancel. */
    var sendStarted: Boolean = false
        private set

    init {
        // A terminal holder value at page-open is left over from a previous attempt, since this view
        // model has not started anything yet. The holder is a process singleton, so a prior Failed,
        // Sent or Cancelled would otherwise be replayed every time the page reopens, pinning a stale
        // error page; the outcome is per-attempt, so clear it on a fresh open. A non-terminal value is
        // a live transfer the user backgrounded, so keep it and let the collector below re-attach.
        if (MigrationTransferStateHolder.state.value.isTerminal()) {
            MigrationTransferStateHolder.reset()
        }
        // Re-attach: fold the live service-owned transfer state into the send UI state.
        viewModelScope.launch {
            MigrationTransferStateHolder.state.collect { mapTransfer(it) }
        }
        viewModelScope.launch {
            MigrationTransferStateHolder.notices.collect { notice ->
                when (notice) {
                    TransferNotice.CANCEL_TOO_LATE ->
                        emit(MigrationP2pSendEffect.Toast(appContext.getString(R.string.migration_p4_cancel_too_late)))
                }
            }
        }
        // A send this process never saw the end of: ask the receiver before showing anything else.
        // Only when nothing is running — a live transfer in the holder is already being watched.
        if (MigrationTransferStateHolder.state.value == TransferState.Idle) {
            viewModelScope.launch {
                val pending = loadPendingVerdict?.invoke() ?: return@launch
                L.i { "[MigrationV2][P2P][Tx] pending verdict found on open — asking the receiver first" }
                recheckVerdict(pending.session)
            }
        }
    }

    fun onIntent(intent: MigrationP2pSendIntent) {
        when (intent) {
            MigrationP2pSendIntent.LaunchScan -> {
                _state.update { MigrationP2pSendState.Scanning }
                emit(MigrationP2pSendEffect.LaunchScanActivity)
            }
            is MigrationP2pSendIntent.QrScanned -> onScanned(intent.raw)
            MigrationP2pSendIntent.ScanCancelled -> _state.update { MigrationP2pSendState.Idle }
            MigrationP2pSendIntent.Send -> startSend()
            MigrationP2pSendIntent.Cancel -> MigrationTransferService.cancel(appContext)
            MigrationP2pSendIntent.Close -> emit(MigrationP2pSendEffect.Finish)
            is MigrationP2pSendIntent.SetIncludeAttachments -> _state.update { s ->
                // The space verdict is a function of the payload, so it must re-run on every toggle —
                // dropping attachments is exactly the remedy the screen offers when it does not fit.
                if (s is MigrationP2pSendState.Ready) withSpaceVerdict(s.copy(includeAttachments = intent.include)) else s
            }
            MigrationP2pSendIntent.ReturnToPeer -> emit(MigrationP2pSendEffect.ReturnToPeerApp)
            MigrationP2pSendIntent.AuthSucceeded -> onAuthSucceeded()
            MigrationP2pSendIntent.AuthFailed -> onAuthFailed()
            MigrationP2pSendIntent.RecheckPeer -> startReconcile()
            MigrationP2pSendIntent.RecheckVerdict -> viewModelScope.launch {
                val pending = loadPendingVerdict?.invoke()
                if (pending == null) {
                    // Nothing to ask about any more: the record aged out, or was never written.
                    _state.update { MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.UNREACHABLE) }
                } else {
                    recheckVerdict(pending.session)
                }
            }
            MigrationP2pSendIntent.DiscardUnconfirmed -> viewModelScope.launch {
                L.i { "[MigrationV2][P2P][Tx] user discarded the unconfirmed send after checking the new device" }
                clearPendingVerdict?.invoke()
                _state.update { MigrationP2pSendState.Idle }
            }
            MigrationP2pSendIntent.RefreshPairing -> requestFreshPairing()
            MigrationP2pSendIntent.RescanLink -> emit(MigrationP2pSendEffect.RescanLink)
            MigrationP2pSendIntent.OpenStorageSettings -> emit(MigrationP2pSendEffect.OpenStorageSettings)
            MigrationP2pSendIntent.OpenUpdate -> emit(MigrationP2pSendEffect.OpenUpdatePage)
        }
    }

    /**
     * The failure screen came back to the front while the new app may have finished behind it. A no-op
     * outside handoff mode and off the failure screen.
     */
    fun onScreenResumed() {
        if (credentialHandoff && _state.value is MigrationP2pSendState.Failed) startReconcile()
        // Back in front with an unconfirmed result: the first thing to do is ask, not to assume.
        val unconfirmed = _state.value as? MigrationP2pSendState.Unconfirmed ?: return
        if (unconfirmed.phase == MigrationP2pSendState.Unconfirmed.Phase.IDLE ||
            unconfirmed.phase == MigrationP2pSendState.Unconfirmed.Phase.UNREACHABLE
        ) {
            onIntent(MigrationP2pSendIntent.RecheckVerdict)
        }
    }

    /**
     * Ask the receiver how the pending send ended and show that. A definite answer — either way —
     * ends the pending record; no answer keeps it, with the page saying to look at the new device.
     */
    private fun recheckVerdict(session: SenderSessionRef) {
        val probe = probeVerdict ?: return
        if (recheckJob?.isActive == true) return
        _state.update { MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.CHECKING) }
        recheckJob = viewModelScope.launch {
            when (val verdict = runCatching { probe(session) }.getOrElse { Verdict.Unknown(Verdict.Unknown.Reason.UNREACHABLE) }) {
                Verdict.Succeeded -> {
                    L.i { "[MigrationV2][P2P][Tx] re-check: the receiver finished — done" }
                    clearPendingVerdict?.invoke()
                    _state.update { MigrationP2pSendState.Done }
                }
                is Verdict.Ended -> {
                    L.i { "[MigrationV2][P2P][Tx] re-check: the receiver ended with ${verdict.terminal}" }
                    clearPendingVerdict?.invoke()
                    _state.update { MigrationP2pSendState.Failed(SenderVerdictTracker.reasonFor(verdict)) }
                }
                is Verdict.Unknown -> {
                    L.w { "[MigrationV2][P2P][Tx] re-check: still unknown (${verdict.reason})" }
                    _state.update { MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.UNREACHABLE) }
                }
            }
        }
    }

    /** Fill in the pairing page's size figures once they are known; a stale result never overwrites a later state. */
    private fun estimateSize() {
        val estimate = estimator ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { estimate() }
                .onFailure { e -> L.w { "[MigrationV2][P2P][Tx] size estimate failed: ${e.message}" } }
                .getOrNull() ?: return@launch
            _state.update { s -> if (s is MigrationP2pSendState.Ready) withSpaceVerdict(s.copy(estimate = result)) else s }
        }
    }

    /**
     * What the holder says about a transfer this screen must not shadow. The scan and the start must
     * both read it and agree, or the screen offers a start the service will refuse. An idle listener
     * does not count as in flight — the service evicts it for a start.
     */
    private fun inFlightOrNull(): TransferState? = MigrationTransferStateHolder.state.value.takeIf {
        it != TransferState.Idle &&
            !it.isTerminal() &&
            it !is TransferState.WaitingForPeer &&
            it != TransferState.Starting
    }

    private fun onScanned(raw: String) {
        // A transfer is still running: this screen is showing it (the holder collector re-attached on
        // open), and a scan must not replace that with a pairing page whose start button the service
        // would refuse. The migration deliberately does not block the app, so being interrupted by a
        // message or a call and coming back through the scanner is an ordinary path, not an edge case.
        inFlightOrNull()?.let { live ->
            L.w { "[MigrationV2][P2P][Tx] scan ignored: transfer in flight (${live::class.simpleName})" }
            emit(MigrationP2pSendEffect.Toast(appContext.getString(R.string.migration_p2p_send_transfer_in_flight)))
            return
        }
        val payload = QrPayloadCodec.decode(raw)
        if (payload == null) {
            emit(MigrationP2pSendEffect.Toast(appContext.getString(R.string.migration_p2p_send_scan_not_p2p)))
            _state.update { MigrationP2pSendState.Idle }
            return
        }
        if (payload.transferKey == null) {
            emit(MigrationP2pSendEffect.Toast(appContext.getString(R.string.migration_p2p_send_scan_no_key)))
            _state.update { MigrationP2pSendState.Idle }
            return
        }
        val resolver = resolveEndpoint
        if (resolver == null || payload.candidates.size <= 1) {
            onTargetSettled(raw, payload)
            return
        }
        // Several addresses advertised: find the one that answers before the first request goes
        // out, and hand the service a code that names only that address, so the claim, the upload
        // and the session record all agree on it. No winner keeps the first address and lets the
        // connection fail where it always did.
        pendingAlias = payload.alias
        _targetAlias.value = payload.alias
        viewModelScope.launch {
            val settled = runCatching { resolver(payload) }
                .onFailure { e -> L.w { "[MigrationV2][P2P][Tx] address race failed: ${e.message}" } }
                .getOrNull()
            val settledRaw = settled?.let { runCatching { QrPayloadCodec.encodeText(it) }.getOrNull() }
            if (settled == null || settledRaw == null) {
                onTargetSettled(raw, payload)
            } else {
                onTargetSettled(settledRaw, settled)
            }
        }
    }

    private fun onTargetSettled(raw: String, payload: QrPayload) {
        scannedRaw = raw
        pendingAlias = payload.alias
        _targetAlias.value = payload.alias
        _sameDevice.value = payload.ip == PeerApps.LOOPBACK_IP
        // Do not log the transfer key.
        L.i { "[MigrationV2][P2P][Tx] scanned target ${payload.alias} @ ${payload.ip}:${payload.port} autoStart=$autoStart" }
        // Handoff: a blocked hand-over (switch off, session gone) is shown instead of a page that
        // could never start. The user-presence step itself waits for the start tap.
        if (credentialHandoff && blockIfNeeded()) return
        claimThenReady(payload)
    }

    /**
     * Tell the receiver its code was read before the confirmation page opens, so the code stops
     * rotating there while the user reads the page. A code that has already rotated is refused now,
     * with the scanner still at hand, instead of after the user has read the page and tapped start.
     * Same device and the Desktop link path go straight to the page. No answer at all means the
     * peer is unreachable (not on one network): fail now rather than after start; a 404 is an accept.
     */
    private fun claimThenReady(payload: QrPayload) {
        val claim = claimPairing
        if (claim == null || _sameDevice.value || autoStart) {
            enterReady()
            return
        }
        viewModelScope.launch {
            when (runCatching { claim(payload) }.getOrElse { SenderHttpClient.PairingClaim.UNREACHABLE }) {
                SenderHttpClient.PairingClaim.EXPIRED -> rescan(R.string.migration_p3_reason_expired)
                SenderHttpClient.PairingClaim.ACCEPTED -> enterReady()
                SenderHttpClient.PairingClaim.UNREACHABLE -> {
                    L.w { "[MigrationV2][P2P][Tx] claim unanswered — peer unreachable" }
                    _state.update { MigrationP2pSendState.Failed(PrepareUploadReasons.PEER_UNREACHABLE) }
                }
            }
        }
    }

    /** The scanned code is no good: say why and open the scanner again. */
    private fun rescan(messageRes: Int) {
        emit(MigrationP2pSendEffect.Toast(appContext.getString(messageRes)))
        scannedRaw = null
        _state.update { MigrationP2pSendState.Scanning }
        emit(MigrationP2pSendEffect.LaunchScanActivity)
    }

    /** Handoff only: the authoritative gate re-check. True when the hand-over cannot proceed. */
    private fun blockIfNeeded(): Boolean {
        val reason = handoffBlockReason?.invoke() ?: return false
        L.w { "[MigrationV2][Handoff] hand-over blocked reason=$reason" }
        _state.update { MigrationP2pSendState.Blocked(reason) }
        return true
    }

    /**
     * Handoff only: the user-presence step, taken when the user taps start so the prompt is tied to
     * the action being authorised rather than to opening the page. Returns true when an auth screen
     * is now on its way and the send must wait for its verdict.
     */
    private fun requestAuth(ready: MigrationP2pSendState.Ready): Boolean {
        val effect = when (authMethod?.invoke() ?: HandoffAuthPolicy.Method.NONE) {
            HandoffAuthPolicy.Method.APP_LOCK -> MigrationP2pSendEffect.RequestAppLockAuth
            HandoffAuthPolicy.Method.DEVICE_CREDENTIAL -> MigrationP2pSendEffect.RequestDeviceCredentialAuth
            // Nothing to verify against on this device; the start tap is the only gate left.
            HandoffAuthPolicy.Method.NONE -> {
                L.i { "[MigrationV2][Handoff] auth skipped: no device lock" }
                return false
            }
        }
        readyBeforeAuth = ready
        _state.update { MigrationP2pSendState.Authenticating }
        emit(effect)
        return true
    }

    private fun onAuthSucceeded() {
        if (_state.value !is MigrationP2pSendState.Authenticating) return
        L.i { "[MigrationV2][Handoff] user presence confirmed" }
        authConfirmed = true
        restoreReadyAfterAuth()
        startSend()
    }

    /** A refused or dismissed prompt returns to the confirmation page; the user can try again. */
    private fun onAuthFailed() {
        if (_state.value !is MigrationP2pSendState.Authenticating) return
        L.w { "[MigrationV2][Handoff] user presence not confirmed — staying on the confirmation page" }
        restoreReadyAfterAuth()
    }

    private fun restoreReadyAfterAuth() {
        readyBeforeAuth?.let { saved -> _state.update { saved } }
        readyBeforeAuth = null
    }

    /** The confirmation page: target, sizes, the attachment choice and the space verdict. */
    private fun enterReady() {
        val alias = pendingAlias ?: return
        // No transfer key on any state field.
        _state.update { MigrationP2pSendState.Ready(alias, includeAttachments = initialIncludeAttachments) }
        // The estimate feeds the progress reading either way, and it is what the page shows if an
        // immediate start cannot proceed (the service refused it) and the user is left here.
        estimateSize()
        if (autoStart) startSend()
    }

    /**
     * Re-evaluate whether the selected payload fits. Cross-device cannot be answered here (the peer's
     * volume is a different device), so the verdict is same-device only and otherwise reads as "fits".
     */
    private fun withSpaceVerdict(ready: MigrationP2pSendState.Ready): MigrationP2pSendState.Ready {
        val check = spaceVerdict ?: return ready
        val estimate = ready.estimate ?: return ready
        if (!_sameDevice.value) return ready
        val verdict = runCatching { check(estimate, ready.includeAttachments) }
            .onFailure { e -> L.w { "[MigrationV2][Handoff] space check failed: ${e.message}" } }
            .getOrNull() ?: return ready
        if (!verdict.sufficient) {
            L.w { "[MigrationV2][Handoff] insufficient storage shortfall=${verdict.shortfallBytes}" }
        }
        return ready.copy(spaceShortfallBytes = verdict.shortfallBytes)
    }

    private fun startSend() {
        val ready = _state.value as? MigrationP2pSendState.Ready
        if (ready != null && !ready.spaceSufficient) {
            // The screen already disables the button; this is the second half of the same rule, so a
            // stale tap or an auto-start cannot slip past it.
            L.w { "[MigrationV2][Handoff] send refused: not enough free space" }
            return
        }
        val raw = scannedRaw
        if (raw == null) {
            emit(MigrationP2pSendEffect.Toast(appContext.getString(R.string.migration_p2p_send_scan_first)))
            return
        }
        // Handoff: re-check the gate at the moment that matters, then prove user presence once. A
        // confirmed auth carries over to a retry from the failure screen in the same session.
        if (credentialHandoff && !authConfirmed) {
            if (blockIfNeeded()) return
            if (ready != null && requestAuth(ready)) return
        }
        // Reset the holder synchronously before the async start, so a stale terminal from a previous
        // transfer is not replayed into this send. The holder is a process singleton shared by both
        // roles, so the rule mirrors the service's: reset from a stale terminal or Idle; leave an idle
        // listener for the service to evict; never shadow a transfer with bytes moving or landing.
        val holder = MigrationTransferStateHolder.state.value
        when {
            holder == TransferState.Idle || holder.isTerminal() -> MigrationTransferStateHolder.reset()
            holder is TransferState.WaitingForPeer || holder == TransferState.Starting ->
                L.i { "[MigrationV2][P2P][Tx] send start: idle listener in the holder, the service evicts it" }
            else -> {
                // Last resort: the scan gate above already turns a re-entry away, so reaching here means
                // the holder changed between that check and this tap. Refusing is right; refusing in
                // silence is not — the button would simply appear dead.
                L.w { "[MigrationV2][P2P][Tx] send start ignored: transfer in flight (${holder::class.simpleName})" }
                emit(MigrationP2pSendEffect.Toast(appContext.getString(R.string.migration_p2p_send_transfer_in_flight)))
                return
            }
        }
        val includeAttachments = ready?.includeAttachments ?: true
        estimatedTotalBytes = ready?.estimate?.let { e ->
            if (includeAttachments || !e.offersAttachmentChoice) e.totalBytes else e.withoutAttachmentsBytes
        } ?: 0L
        L.i { "[MigrationV2][P2P][Tx] send start includeAttachments=$includeAttachments handoff=$credentialHandoff estimate=$estimatedTotalBytes" }
        MigrationTransferService.startSender(appContext, raw, includeAttachments, credentialHandoff, awaitPeerConfirm)
        sendStarted = true
        // Same device: the user follows progress on the new app; the sender keeps running in its FGS.
        if (_sameDevice.value) emit(MigrationP2pSendEffect.BringPeerToFront)
    }

    /** Maps the role-agnostic transfer state into the sender UI state. */
    private fun mapTransfer(transfer: TransferState) = _state.update { current ->
        when (transfer) {
            // Idle is the pre-transfer reset state; keep whatever local pre-transfer state we are in.
            TransferState.Idle -> current
            TransferState.Starting -> MigrationP2pSendState.Preparing
            // The reading is measured against this end's own size estimate: the service does not
            // know it, and the stream it counts is the one the estimate describes.
            is TransferState.Transferring -> MigrationP2pSendState.Transferring(transfer.progress, estimatedTotalBytes)
            TransferState.AwaitingVerdict -> MigrationP2pSendState.AwaitingVerdict
            TransferState.VerdictStalled -> MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.WAITING)
            // The service gave up waiting; the pending record stays for the user's own re-check.
            TransferState.Unknown -> MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.IDLE)
            TransferState.AwaitingPeerConfirm -> MigrationP2pSendState.AwaitingPeerConfirm
            // Receiver-only states, never reached on the send screen; benign no-op.
            is TransferState.WaitingForPeer,
            TransferState.Promoting,
            TransferState.Promoted,
            is TransferState.AwaitingDesktopConfirm,
            is TransferState.AwaitingDesktopConnect,
            TransferState.CommittedUnconfirmed -> current
            TransferState.Sent -> MigrationP2pSendState.Done
            is TransferState.Failed -> {
                L.w { "[MigrationV2][P2P][Tx] send failed reason=${transfer.reason}" }
                if (credentialHandoff && reconcile != null) {
                    // No 200 does not prove the new app did not finish. Ask it before offering a
                    // restart; until it answers the screen shows "recovering" and offers nothing.
                    startReconcile()
                    MigrationP2pSendState.Failed(transfer.reason, retryEnabled = false)
                } else {
                    MigrationP2pSendState.Failed(transfer.reason)
                }
            }
            TransferState.Cancelled -> MigrationP2pSendState.Cancelled
        }
    }

    /**
     * Asks the new app whether it adopted the session and reflects the answer in the failure screen:
     * only a reachable peer that did not adopt makes a restart safe. An unreachable peer leaves the
     * state genuinely unknown, so the restart stays closed and a re-check is offered.
     */
    private fun startReconcile() {
        val ask = reconcile ?: return
        if (reconcileJob?.isActive == true) return
        _state.update { s ->
            if (s is MigrationP2pSendState.Failed) s.copy(retryEnabled = false, reconcileOutcome = null) else s
        }
        reconcileJob = viewModelScope.launch {
            val outcome = runCatching { ask() }
                .onFailure { e -> L.w { "[MigrationV2][Handoff] reconcile failed: ${e.message}" } }
                .getOrDefault(MigrationHandoffReconciler.Outcome.UNKNOWN)
            L.i { "[MigrationV2][Handoff] reconcile outcome=$outcome" }
            // Signed out or already done: this process is on its way out, so publishing a new state
            // would only make the dying screen flicker.
            if (outcome == MigrationHandoffReconciler.Outcome.SIGNED_OUT ||
                outcome == MigrationHandoffReconciler.Outcome.ALREADY_DONE
            ) {
                return@launch
            }
            val retryEnabled = outcome != MigrationHandoffReconciler.Outcome.UNKNOWN
            _state.update { s ->
                if (s is MigrationP2pSendState.Failed) s.copy(retryEnabled = retryEnabled, reconcileOutcome = outcome) else s
            }
        }
    }

    /** Only the same-device hand-over has a peer to ask; every other path retries by scanning. */
    private fun requestFreshPairing() {
        if (!credentialHandoff) {
            L.w { "[MigrationV2][P2P][Tx] fresh pairing ignored: not a credential handoff" }
            return
        }
        emit(MigrationP2pSendEffect.RequestFreshPairing)
    }

    /**
     * The new app answered with a fresh pairing payload: pair with it through the ordinary scan path
     * and start at once, so the retry costs one tap. Same-device pairing skips the claim and lands on
     * the confirmation page synchronously; anything else means the payload was refused on the way in,
     * and the state that produced (blocked, failed) is what the screen should keep showing. The
     * proven user presence carries over within the session, so there is no second prompt.
     */
    fun onFreshPairing(raw: String) {
        onIntent(MigrationP2pSendIntent.QrScanned(raw))
        val paired = _state.value
        if (paired !is MigrationP2pSendState.Ready) {
            L.w { "[MigrationV2][P2P][Tx] fresh pairing did not re-pair state=${paired::class.simpleName}" }
            return
        }
        L.i { "[MigrationV2][P2P][Tx] fresh pairing accepted — restarting the send" }
        onIntent(MigrationP2pSendIntent.Send)
    }

    private fun emit(effect: MigrationP2pSendEffect) {
        viewModelScope.launch { _effects.send(effect) }
    }
}
