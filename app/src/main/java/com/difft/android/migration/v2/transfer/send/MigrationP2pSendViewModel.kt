package com.difft.android.migration.v2.transfer.send

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.R
import com.difft.android.p2p.engine.model.TransferDirection
import com.difft.android.p2p.engine.model.direction
import com.difft.android.chat.R as ChatR
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













class MigrationP2pSendViewModel(
    private val appContext: Context,
    
    private val estimator: (suspend () -> ExportEstimate)? = null,
    
    
    private val autoStart: Boolean = false,
    private val initialIncludeAttachments: Boolean = true,
    
    
    private val credentialHandoff: Boolean = false,
    
    
    private val handoffBlockReason: (() -> String?)? = null,
    
    private val authMethod: (() -> HandoffAuthPolicy.Method)? = null,
    
    
    private val spaceVerdict: ((ExportEstimate, Boolean) -> HandoffSpaceCheck.Verdict)? = null,
    
    private val reconcile: (suspend () -> MigrationHandoffReconciler.Outcome)? = null,
    
    
    private val awaitPeerConfirm: Boolean = false,
    
    
    private val resolveEndpoint: (suspend (QrPayload) -> QrPayload?)? = null,
    
    private val claimPairing: (suspend (QrPayload) -> SenderHttpClient.PairingClaim)? = null,
    
    private val loadPendingVerdict: (suspend () -> PendingVerdict?)? = null,
    private val clearPendingVerdict: (suspend () -> Unit)? = null,
    
    private val probeVerdict: (suspend (SenderSessionRef) -> Verdict)? = null,
    
    
    
    private val settleVerdict: (suspend (SenderSessionRef, Verdict) -> Boolean)? = null,
    
    private val deviceReplacement: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow<MigrationP2pSendState>(MigrationP2pSendState.Idle)
    val state: StateFlow<MigrationP2pSendState> = _state.asStateFlow()

    
    private val _targetAlias = MutableStateFlow<String?>(null)
    val targetAlias: StateFlow<String?> = _targetAlias.asStateFlow()

    
    private val _sameDevice = MutableStateFlow(false)
    val sameDevice: StateFlow<Boolean> = _sameDevice.asStateFlow()

    private val _effects = Channel<MigrationP2pSendEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    
    private var scannedRaw: String? = null

    
    private var pendingAlias: String? = null

    
    private var reconcileJob: Job? = null

    
    private var recheckJob: Job? = null

    
    private var readyBeforeAuth: MigrationP2pSendState.Ready? = null

    
    private var authConfirmed = false

    
    private var estimatedTotalBytes = 0L

    
    var sendStarted: Boolean = false
        private set

    init {
        
        
        
        
        
        if (MigrationTransferStateHolder.state.value.isTerminal()) {
            MigrationTransferStateHolder.reset()
        }
        
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
                
                
                if (s is MigrationP2pSendState.Ready) withSpaceVerdict(s.copy(includeAttachments = intent.include)) else s
            }
            MigrationP2pSendIntent.ReturnToPeer -> emit(MigrationP2pSendEffect.ReturnToPeerApp)
            MigrationP2pSendIntent.AuthSucceeded -> onAuthSucceeded()
            MigrationP2pSendIntent.AuthFailed -> onAuthFailed()
            MigrationP2pSendIntent.RecheckPeer -> startReconcile()
            MigrationP2pSendIntent.RecheckVerdict -> viewModelScope.launch {
                val pending = loadPendingVerdict?.invoke()
                if (pending == null) {
                    
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

    



    fun onScreenResumed() {
        if (credentialHandoff && _state.value is MigrationP2pSendState.Failed) startReconcile()
        
        val unconfirmed = _state.value as? MigrationP2pSendState.Unconfirmed ?: return
        if (unconfirmed.phase == MigrationP2pSendState.Unconfirmed.Phase.IDLE ||
            unconfirmed.phase == MigrationP2pSendState.Unconfirmed.Phase.UNREACHABLE
        ) {
            onIntent(MigrationP2pSendIntent.RecheckVerdict)
        }
    }

    



    private fun recheckVerdict(session: SenderSessionRef) {
        val probe = probeVerdict ?: return
        if (recheckJob?.isActive == true) return
        _state.update { MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.CHECKING) }
        recheckJob = viewModelScope.launch {
            val verdict = runCatching { probe(session) }.getOrElse { Verdict.Unknown(Verdict.Unknown.Reason.UNREACHABLE) }
            if (!settle(session, verdict)) {
                L.w { "[MigrationV2][P2P][Tx] event=recheck_verdict_not_applied" }
                _state.update { MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.UNREACHABLE) }
                return@launch
            }
            when (verdict) {
                Verdict.Succeeded -> {
                    L.i { "[MigrationV2][P2P][Tx] re-check: the receiver finished — done" }
                    _state.update { MigrationP2pSendState.Done }
                }
                is Verdict.Ended -> {
                    L.i { "[MigrationV2][P2P][Tx] re-check: the receiver ended with ${verdict.terminal}" }
                    _state.update { MigrationP2pSendState.Failed(SenderVerdictTracker.reasonFor(verdict)) }
                }
                is Verdict.Unknown -> {
                    L.w { "[MigrationV2][P2P][Tx] re-check: still unknown (${verdict.reason})" }
                    _state.update { MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.UNREACHABLE) }
                }
            }
        }
    }

    



    private suspend fun settle(session: SenderSessionRef, verdict: Verdict): Boolean {
        val settlement = settleVerdict
        if (settlement != null) return settlement(session, verdict)
        if (verdict !is Verdict.Unknown) clearPendingVerdict?.invoke()
        return true
    }

    
    private fun estimateSize() {
        val estimate = estimator ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { estimate() }
                .onFailure { e -> L.w { "[MigrationV2][P2P][Tx] size estimate failed: ${e.message}" } }
                .getOrNull() ?: return@launch
            _state.update { s -> if (s is MigrationP2pSendState.Ready) withSpaceVerdict(s.copy(estimate = result)) else s }
        }
    }

    




    private fun inFlightOrNull(): TransferState? = MigrationTransferStateHolder.state.value.takeIf {
        it != TransferState.Idle &&
            !it.isTerminal() &&
            it !is TransferState.WaitingForPeer &&
            it != TransferState.Starting
    }

    private fun onScanned(raw: String) {
        
        
        
        
        
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
        
        
        if (payload.direction == TransferDirection.DOWNLOAD) {
            L.w { "[MigrationV2][P2P][Tx] scan declined kind=computer_export" }
            emit(MigrationP2pSendEffect.Toast(appContext.getString(ChatR.string.scan_computer_export_code_hint)))
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
        
        L.i { "[MigrationV2][P2P][Tx] scanned target ${payload.alias} @ ${payload.ip}:${payload.port} autoStart=$autoStart" }
        
        
        if (credentialHandoff && blockIfNeeded()) return
        claimThenReady(payload)
    }

    






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

    
    private fun rescan(messageRes: Int) {
        emit(MigrationP2pSendEffect.Toast(appContext.getString(messageRes)))
        scannedRaw = null
        _state.update { MigrationP2pSendState.Scanning }
        emit(MigrationP2pSendEffect.LaunchScanActivity)
    }

    
    private fun blockIfNeeded(): Boolean {
        val reason = handoffBlockReason?.invoke() ?: return false
        L.w { "[MigrationV2][Handoff] hand-over blocked reason=$reason" }
        _state.update { MigrationP2pSendState.Blocked(reason) }
        return true
    }

    




    private fun requestAuth(ready: MigrationP2pSendState.Ready): Boolean {
        val effect = when (authMethod?.invoke() ?: HandoffAuthPolicy.Method.NONE) {
            HandoffAuthPolicy.Method.APP_LOCK -> MigrationP2pSendEffect.RequestAppLockAuth
            HandoffAuthPolicy.Method.DEVICE_CREDENTIAL -> MigrationP2pSendEffect.RequestDeviceCredentialAuth
            
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

    
    private fun onAuthFailed() {
        if (_state.value !is MigrationP2pSendState.Authenticating) return
        L.w { "[MigrationV2][Handoff] user presence not confirmed — staying on the confirmation page" }
        restoreReadyAfterAuth()
    }

    private fun restoreReadyAfterAuth() {
        readyBeforeAuth?.let { saved -> _state.update { saved } }
        readyBeforeAuth = null
    }

    
    private fun enterReady() {
        val alias = pendingAlias ?: return
        
        _state.update { MigrationP2pSendState.Ready(alias, includeAttachments = initialIncludeAttachments) }
        
        
        estimateSize()
        if (autoStart) startSend()
    }

    



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
            
            
            L.w { "[MigrationV2][Handoff] send refused: not enough free space" }
            return
        }
        val raw = scannedRaw
        if (raw == null) {
            emit(MigrationP2pSendEffect.Toast(appContext.getString(R.string.migration_p2p_send_scan_first)))
            return
        }
        
        
        if (credentialHandoff && !authConfirmed) {
            if (blockIfNeeded()) return
            if (ready != null && requestAuth(ready)) return
        }
        
        
        
        
        val holder = MigrationTransferStateHolder.state.value
        when {
            holder == TransferState.Idle || holder.isTerminal() -> MigrationTransferStateHolder.reset()
            holder is TransferState.WaitingForPeer || holder == TransferState.Starting ->
                L.i { "[MigrationV2][P2P][Tx] send start: idle listener in the holder, the service evicts it" }
            else -> {
                
                
                
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
        MigrationTransferService.startSender(appContext, raw, includeAttachments, credentialHandoff, awaitPeerConfirm, deviceReplacement)
        sendStarted = true
        
        if (_sameDevice.value) emit(MigrationP2pSendEffect.BringPeerToFront)
    }

    
    private fun mapTransfer(transfer: TransferState) = _state.update { current ->
        when (transfer) {
            
            TransferState.Idle -> current
            TransferState.Starting -> MigrationP2pSendState.Preparing
            
            
            is TransferState.Transferring -> MigrationP2pSendState.Transferring(transfer.progress, estimatedTotalBytes)
            TransferState.AwaitingVerdict -> MigrationP2pSendState.AwaitingVerdict
            TransferState.VerdictStalled -> MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.WAITING)
            
            TransferState.Unknown -> MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.IDLE)
            TransferState.AwaitingPeerConfirm -> MigrationP2pSendState.AwaitingPeerConfirm
            
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
                    
                    
                    startReconcile()
                    MigrationP2pSendState.Failed(transfer.reason, retryEnabled = false)
                } else {
                    MigrationP2pSendState.Failed(transfer.reason)
                }
            }
            TransferState.Cancelled -> MigrationP2pSendState.Cancelled
        }
    }

    




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

    
    private fun requestFreshPairing() {
        if (!credentialHandoff) {
            L.w { "[MigrationV2][P2P][Tx] fresh pairing ignored: not a credential handoff" }
            return
        }
        emit(MigrationP2pSendEffect.RequestFreshPairing)
    }

    






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
