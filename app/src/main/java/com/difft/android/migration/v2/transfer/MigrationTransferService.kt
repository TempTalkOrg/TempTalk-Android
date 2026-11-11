package com.difft.android.migration.v2.transfer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.difft.android.BuildConfig
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.linkeddevices.LinkedDevicesCountStore
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.snapshot.BackupSnapshotter
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.handoff.MigrationHandoffCompleter
import com.difft.android.migration.v2.handoff.MigrationHandoffGate
import com.difft.android.migration.v2.handoff.MigrationHandoffReconciler
import com.difft.android.migration.v2.login.MigrationImportLauncherImpl
import com.difft.android.migration.v2.pause.SenderMessagePause
import com.difft.android.migration.v2.pause.SenderPauseRun
import com.difft.android.migration.v2.pause.SenderVerdictSettlement
import com.difft.android.migration.v2.pause.SettleResult
import com.difft.android.migration.v2.restore.RestoreOrchestrator
import com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveController
import com.difft.android.migration.v2.transfer.login.ReversePairController
import com.difft.android.migration.v2.transfer.send.HANDOFF_NOT_ACKNOWLEDGED
import com.difft.android.migration.v2.transfer.send.MigrationP2pSendController
import com.difft.android.migration.v2.transfer.send.classifyFailure
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.p2p.android.client.EndpointRace
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.PrepareUploadRejectedException
import com.difft.android.p2p.android.qr.QrPayloadCodec
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.upload.UploadReplyHeaders
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import dagger.hilt.android.AndroidEntryPoint
import java.io.EOFException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.inject.Inject
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull










@AndroidEntryPoint
class MigrationTransferService : LifecycleService() {

    @Inject lateinit var containerWriter: ContainerWriter
    @Inject lateinit var containerReader: ContainerReader
    @Inject lateinit var backupCrypto: BackupCrypto
    @Inject lateinit var restoreOrchestrator: RestoreOrchestrator
    @Inject lateinit var environmentHelper: EnvironmentHelper
    @Inject lateinit var snapshotter: BackupSnapshotter
    @Inject lateinit var notificationHelper: MigrationTransferNotificationHelper

    
    @Inject lateinit var userManager: StorageBoundUserManager

    
    
    @Inject lateinit var handoffCompleter: MigrationHandoffCompleter

    
    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var linkedDevicesCountStore: LinkedDevicesCountStore
    @Inject lateinit var handoffReconciler: MigrationHandoffReconciler
    @Inject lateinit var handoffGate: MigrationHandoffGate
    @Inject lateinit var migrationImportLauncher: MigrationImportLauncher

    
    @Inject lateinit var pendingVerdictStore: PendingVerdictStore

    
    @Inject lateinit var senderMessagePause: SenderMessagePause
    @Inject lateinit var verdictSettlement: SenderVerdictSettlement

    
    @Volatile private var pauseRun: SenderPauseRun? = null

    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    



    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    
    private val verdicts by lazy { SenderVerdictTracker(pendingVerdictStore, controlScope) }

    
    private val receiverEvents = ReceiverRoleEvents { percent -> notificationHelper.updateProgress(percent) }

    
    private val pairing = ReceiverPairing(PAIRING_TIMEOUT_MS, PAIRING_ROTATE_INTERVAL_MS)

    
    private val transferLocks by lazy { TransferLocks(this) }

    
    private val terminalNotices by lazy { TransferTerminalNotices(this, notificationHelper) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_RECEIVER ->
                startReceiverRole(
                    account = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty(),
                    sameDevice = intent.getBooleanExtra(EXTRA_SAME_DEVICE, false),
                    credentialHandoff = intent.getBooleanExtra(EXTRA_CREDENTIAL_HANDOFF, false),
                )

            ACTION_START_REVERSE_PAIR -> pairing.requestScan(intent.getStringExtra(EXTRA_QR_PAYLOAD).orEmpty())

            ACTION_START_SENDER ->
                startSenderRole(
                    raw = intent.getStringExtra(EXTRA_QR_PAYLOAD).orEmpty(),
                    includeAttachments = intent.getBooleanExtra(EXTRA_INCLUDE_ATTACHMENTS, true),
                    credentialHandoff = intent.getBooleanExtra(EXTRA_CREDENTIAL_HANDOFF, false),
                    awaitPeerConfirm = intent.getBooleanExtra(EXTRA_AWAIT_PEER_CONFIRM, false),
                    
                    deviceReplacement = intent.getBooleanExtra(EXTRA_DEVICE_REPLACEMENT, false),
                )

            ACTION_CANCEL -> cancelTransfer()

            else -> {
                L.w { "[MigrationV2][P2P][FGS] unknown action=${intent?.action} — stopping" }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    

    private fun startReceiverRole(account: String, sameDevice: Boolean, credentialHandoff: Boolean = false) {
        L.i { "[MigrationV2][P2P][FGS] start role=RECEIVER sameDevice=$sameDevice handoff=$credentialHandoff" }
        
        
        notificationHelper.returnToTransferIntent = returnIntent(
            MigrationImportLauncherImpl.createP2pReceiveIntentFor(this, account, sameDevice, credentialHandoff = credentialHandoff),
        )
        
        
        
        
        if (!startForegroundCompat()) {
            L.w { "[MigrationV2][P2P][FGS] startForeground refused — abort receiver" }
            return
        }
        
        
        
        if (isTransferInFlight() && !evictIdleListener()) {
            L.w { "[MigrationV2][P2P][FGS] start ignored — transfer already in flight" }
            return
        }
        MigrationTransferStateHolder.reset()
        MigrationTransferStateHolder.update(TransferState.Starting)
        pairing.resetScan()
        
        transferLocks.acquire(wifi = !sameDevice)
        serviceScope.launch { runReceiver(account, sameDevice, credentialHandoff) }
    }

    @VisibleForTesting
    internal suspend fun runReceiver(
        account: String,
        sameDevice: Boolean = false,
        credentialHandoff: Boolean = false,
    ) {
        val controller = MigrationP2pReceiveController(
            context = applicationContext,
            writer = containerWriter,
            reader = containerReader,
            crypto = backupCrypto,
            orchestrator = restoreOrchestrator,
            environmentHelper = environmentHelper,
            appVersion = BuildConfig.VERSION_NAME,
            loggingInAccount = account,
            credentialHandoff = credentialHandoff,
            handoffGate = handoffGate,
            isFreshTarget = migrationImportLauncher::isFreshInstallTarget,
        )
        receiverEvents.attach(serviceScope)
        try {
            val handle = controller.startReceiver(
                scope = serviceScope, 
                sameDevice = sameDevice,
                onEvent = receiverEvents::onServerEvent,
                onProgress = receiverEvents::onProgress,
                onBodyComplete = ::publishPromoting,
                onPromoted = {
                    
                    
                    MigrationTransferStateHolder.update(TransferState.Promoting)
                    MigrationTransferStateHolder.update(TransferState.Promoted)
                },
                
                
                onCommittedUnconfirmed = {
                    MigrationTransferStateHolder.update(TransferState.CommittedUnconfirmed)
                },
                
                onRejected = { if (isPromoting()) receiverEvents.fail(it, fromOutcome = true) else receiverEvents.failEventually(it) },
            )
            val target = handle.displayTarget
            MigrationTransferStateHolder.publishQr(target) 
            MigrationTransferStateHolder.update(
                TransferState.WaitingForPeer(target.ip, target.port, target.pin),
            )
            L.i { "[MigrationV2][P2P][FGS] receiver up ip=${target.ip} port=${target.port}" }
            
            
            val rotation = if (sameDevice) null else serviceScope.launch { pairing.rotatePin(controller) }
            
            
            
            
            
            val macTarget = pairing.awaitPeerOrScan()
            rotation?.cancel()
            if (macTarget != null) {
                runReversePair(account, handle, macTarget)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            L.e { "[MigrationV2][P2P][FGS] receiver failed: ${e.describeFailure()}" }
            MigrationTransferStateHolder.update(TransferState.Failed(failureReason(e)))
        } finally {
            receiverEvents.detach()
            
            
            
            
            when (MigrationTransferStateHolder.state.value) {
                TransferState.Promoted -> controller.lingerReceiver()
                TransferState.Cancelled, is TransferState.Failed -> controller.lingerAfterUnsuccessfulEnd()
                else -> controller.stopReceiver() 
            }
            finishRole()
        }
    }

    




    private suspend fun runReversePair(
        account: String,
        handle: MigrationP2pReceiveController.ReceiverHandle,
        mac: QrPayload,
    ) {
        ReversePairController(
            appContext = applicationContext,
            registry = handle.pairingRegistry,
            environmentHelper = environmentHelper,
            appVersion = BuildConfig.VERSION_NAME,
            account = account,
        ).run(scope = serviceScope, mac = mac, listen = handle.displayTarget)
        MigrationTransferStateHolder.state.first { it.isTerminal() }
    }

    






    private fun advanceLiveSender(next: TransferState) {
        val current = MigrationTransferStateHolder.state.value
        if (current.acceptsSenderCallback()) {
            MigrationTransferStateHolder.update(next)
        } else {
            L.w { "[MigrationV2][P2P][FGS] sender callback after ${current::class.simpleName} ignored: ${next::class.simpleName}" }
        }
    }

    

    private fun startSenderRole(
        raw: String,
        includeAttachments: Boolean,
        credentialHandoff: Boolean = false,
        awaitPeerConfirm: Boolean = false,
        deviceReplacement: Boolean = false,
    ) {
        L.i {
            "[MigrationV2][P2P][FGS] start role=SENDER includeAttachments=$includeAttachments " +
                "handoff=$credentialHandoff awaitPeerConfirm=$awaitPeerConfirm deviceReplacement=$deviceReplacement"
        }
        
        notificationHelper.returnToTransferIntent = returnIntent(
            MigrationImportLauncherImpl.createP2pSendIntentFor(this),
        )
        
        
        
        if (!startForegroundCompat()) {
            L.w { "[MigrationV2][P2P][FGS] startForeground refused — abort sender" }
            return
        }
        
        if (isTransferInFlight() && !evictIdleListener()) {
            L.w { "[MigrationV2][P2P][FGS] start ignored — transfer already in flight" }
            return
        }
        val target: QrPayload? = QrPayloadCodec.decode(raw)
        if (target == null) {
            L.w { "[MigrationV2][P2P][FGS] sender start with undecodable QR — stopping" }
            MigrationTransferStateHolder.reset()
            MigrationTransferStateHolder.update(TransferState.Failed("invalid QR payload"))
            
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        MigrationTransferStateHolder.reset()
        MigrationTransferStateHolder.update(TransferState.Starting)
        transferLocks.acquire()
        serviceScope.launch { runSender(target, includeAttachments, credentialHandoff, awaitPeerConfirm, deviceReplacement) }
    }

    



    private fun isTransferInFlight(): Boolean {
        val current = MigrationTransferStateHolder.state.value
        return current != TransferState.Idle && !current.isTerminal()
    }

    






    private fun evictIdleListener(): Boolean {
        val current = MigrationTransferStateHolder.state.value
        if (current !is TransferState.WaitingForPeer && current != TransferState.Starting) return false
        L.i { "[MigrationV2][P2P][FGS] evicting the idle receiver (${current::class.simpleName}) for a new start" }
        serviceScope.coroutineContext.cancelChildren()
        transferLocks.release()
        MigrationTransferStateHolder.reset()
        return true
    }

    @VisibleForTesting
    internal suspend fun runSender(
        target: QrPayload,
        includeAttachments: Boolean = true,
        credentialHandoff: Boolean = false,
        awaitPeerConfirm: Boolean = false,
        deviceReplacement: Boolean = false,
    ) {
        
        
        val handoffId = if (credentialHandoff) UUID.randomUUID().toString() else ""
        if (credentialHandoff) {
            userManager.update(commit = true) { migrationHandoffId = handoffId }
        }
        val pause = newPauseRun(deviceReplacement, credentialHandoff, handoffId).also { pauseRun = it }
        try {
            MigrationTransferStateHolder.update(
                TransferState.Transferring(TransferState.Role.SENDER, INDETERMINATE),
            )
            
            
            
            
            val target = EndpointRace.resolve(target)
                ?: throw java.net.SocketException("none of the ${target.candidates.size} advertised addresses answered")
            val onProgress: (Float) -> Unit = { ratio ->
                advanceLiveSender(TransferState.Transferring(TransferState.Role.SENDER, ratio))
                
                
                if (ratio > 0f) {
                    notificationHelper.updateProgress((ratio * 100).toInt())
                }
            }
            val onBodySent: () -> Unit = {
                L.i { "[MigrationV2][P2P][FGS] body sent — awaiting the receiver's verdict" }
                advanceLiveSender(TransferState.AwaitingVerdict)
                
                verdicts.onBodySent(persist = pause.session == null)
            }
            verdicts.reset()
            
            
            var handoffAcknowledged = false
            var receiverRejected = false
            val ok = MigrationP2pSendController(
                context = applicationContext,
                writer = containerWriter,
                reader = containerReader,
                crypto = backupCrypto,
                snapshotter = snapshotter,
                environmentHelper = environmentHelper,
                userManager = userManager,
                appVersion = BuildConfig.VERSION_NAME,
            ).sendRealExport(
                scope = serviceScope,
                target = target,
                onProgress = onProgress,
                onBodySent = onBodySent,
                includeAttachments = includeAttachments,
                credentialHandoff = credentialHandoff,
                handoffId = handoffId,
                awaitPeerConfirm = awaitPeerConfirm,
                onAwaitingPeerConfirm = { advanceLiveSender(TransferState.AwaitingPeerConfirm) },
                onReply = { headers -> handoffAcknowledged = UploadReplyHeaders.handoffInstalled(headers) },
                onRejected = { receiverRejected = true },
                onSessionPrepared = { session ->
                    verdicts.onPrepared(session)
                    pause.onSessionPrepared(session)
                },
                verdictWatch = VerdictWatch(),
                onVerdictStall = { stalled ->
                    advanceVerdictWait(if (stalled) TransferState.VerdictStalled else TransferState.AwaitingVerdict)
                },
                pauseSession = pause.session,
            )
            val handoffInstalled = credentialHandoff && ok && handoffAcknowledged
            val settled = pause.settleResult(ok, handoffUnacknowledged = credentialHandoff && !handoffAcknowledged, receiverRejected)
            MigrationTransferStateHolder.update(
                pausedTerminal(settled, pause, credentialHandoff, resultTerminal(ok, credentialHandoff, handoffAcknowledged)),
            )
            if (credentialHandoff) {
                if (handoffInstalled) {
                    
                    
                    
                    handoffCompleter.complete(handoffId)
                    return
                }
                if (ok) L.w { "[MigrationV2][P2P][FGS] handoff answered 200 without the installed acknowledgment — keeping this session" }
                
                
                
                
                reconcileAfterHandoffFailure()
            }
            
            
            
        } catch (e: CancellationException) {
            
            
            
            if (credentialHandoff) {
                userManager.update(commit = true) { migrationHandoffId = "" }
            }
            val settled = pause.settleCancel() 
            correctCancelledTerminal(settled, pause, credentialHandoff)
            throw e
        } catch (e: SendVerdictException) {
            
            
            val settled = pause.settleVerdict(e.verdict)
            MigrationTransferStateHolder.update(pausedTerminal(settled, pause, credentialHandoff, verdicts.stateFor(e.verdict)))
            if (credentialHandoff) reconcileAfterHandoffFailure()
        } catch (e: Throwable) {
            L.e { "[MigrationV2][P2P][FGS] sender failed: ${e.describeFailure()}" }
            val settled = pause.settleFailure()
            MigrationTransferStateHolder.update(pausedTerminal(settled, pause, credentialHandoff, TransferState.Failed(failureReason(e))))
            if (credentialHandoff) reconcileAfterHandoffFailure()
            if ((e as? PrepareUploadRejectedException)?.reason == PrepareUploadReasons.PEER_CANCELLED) refreshLinkedDevices()
        } finally {
            finishRole()
        }
    }

    




    private fun cancelTransfer() {
        if (!verdicts.mustAskReceiver(MigrationTransferStateHolder.state.value)) {
            L.i { "[MigrationV2][P2P][FGS] ACTION_CANCEL — tearing down transfer" }
            tearDown(TransferState.Cancelled)
            return
        }
        L.i { "[MigrationV2][P2P][FGS] ACTION_CANCEL while awaiting the verdict — asking the receiver" }
        controlScope.launch {
            val decision = verdicts.askCancel()
            if (MigrationTransferStateHolder.state.value.isTerminal()) return@launch 
            when (decision) {
                SenderVerdictTracker.CancelDecision.KEEP_WAITING -> MigrationTransferStateHolder.notify(TransferNotice.CANCEL_TOO_LATE)
                SenderVerdictTracker.CancelDecision.CANCELLED -> tearDown(TransferState.Cancelled, decision)
                SenderVerdictTracker.CancelDecision.UNKNOWN -> tearDown(TransferState.Unknown, decision)
            }
        }
    }

    




    private fun tearDown(terminal: TransferState, asked: SenderVerdictTracker.CancelDecision? = null) {
        pauseRun?.askedCancel = asked
        MigrationTransferStateHolder.update(terminal)
        val roles = serviceScope.coroutineContext.job.children.toList()
        serviceScope.coroutineContext.cancelChildren()
        if (roles.isEmpty()) {
            finishTearDown(terminal)
            return
        }
        controlScope.launch {
            withTimeoutOrNull(TEARDOWN_SETTLE_TIMEOUT_MS) { roles.joinAll() }
                ?: L.w { "[MigrationV2][P2P][FGS] cancelled role still running after ${TEARDOWN_SETTLE_TIMEOUT_MS}ms — stopping" }
            finishTearDown(terminal)
        }
    }

    




    private fun pausedTerminal(settled: SettleResult, run: SenderPauseRun, credentialHandoff: Boolean, definite: TransferState): TransferState =
        if (!credentialHandoff && (!settled.proceeds || run.session?.awaitsDecision == true)) TransferState.Unknown else definite

    
    private fun correctCancelledTerminal(settled: SettleResult, run: SenderPauseRun, credentialHandoff: Boolean) {
        if (MigrationTransferStateHolder.state.value != TransferState.Cancelled) return
        if (pausedTerminal(settled, run, credentialHandoff, TransferState.Cancelled) == TransferState.Unknown) {
            MigrationTransferStateHolder.update(TransferState.Unknown)
        }
    }

    
    private fun resultTerminal(ok: Boolean, credentialHandoff: Boolean, handoffAcknowledged: Boolean): TransferState = when {
        !ok -> TransferState.Failed("upload not confirmed (no 200)")
        credentialHandoff && !handoffAcknowledged -> TransferState.Failed(HANDOFF_NOT_ACKNOWLEDGED)
        else -> TransferState.Sent
    }

    




    private fun newPauseRun(deviceReplacement: Boolean, credentialHandoff: Boolean, handoffId: String): SenderPauseRun {
        val session = if (deviceReplacement) {
            senderMessagePause.newSession(handoffId.ifEmpty { UUID.randomUUID().toString() }, persistReceipt = !credentialHandoff)
        } else {
            null
        }
        return SenderPauseRun(session, verdictSettlement)
    }

    private fun finishTearDown(terminal: TransferState) {
        val sessionId = pauseRun?.sessionId
        if (terminal == TransferState.Unknown) postTerminalNotification() else controlScope.launch { verdictSettlement.releasePending(sessionId) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    
    private fun advanceVerdictWait(next: TransferState) {
        if (MigrationTransferStateHolder.state.value.isAwaitingVerdict()) MigrationTransferStateHolder.update(next)
    }

    





    private suspend fun finishRole() {
        if (!currentCoroutineContext().isActive) {
            L.i { "[MigrationV2][P2P][FGS] role cancelled — the service stays with its owner" }
            return
        }
        postTerminalNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    





    



    private suspend fun refreshLinkedDevices() {
        runCatching { deviceRepository.getDevices() }
            .onSuccess { linkedDevicesCountStore.update(it.size) }
            .onFailure { L.w { "[MigrationV2][P2P][FGS] linked-devices refresh after peer cancel failed: ${it.message}" } }
    }

    private suspend fun reconcileAfterHandoffFailure() {
        runCatching { handoffReconciler.onSendFailure() }
            .onFailure { L.w { "[MigrationV2][P2P][FGS] handoff reconcile failed: ${it.message}" } }
    }


    
    private fun Throwable.describeFailure(): String =
        if (isPeerUnreachable()) "could not reach the peer: $message" else stackTraceToString()

    private fun postTerminalNotification() = terminalNotices.post(MigrationTransferStateHolder.state.value)

    @VisibleForTesting
    internal fun localizedFailureReason(reason: String): String = terminalNotices.localizedFailureReason(reason)

    

    



    




    private fun returnIntent(target: Intent): PendingIntent {
        target.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_RETURN_TO_TRANSFER,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    



    private fun startForegroundCompat(): Boolean {
        val channelId = NotificationChannelSpecs.serviceForegroundChannel(this, SERVICE_TAG)
        if (channelId == null) {
            stopSelf()
            return false
        }
        val n = notificationHelper.buildRunning(INDETERMINATE_PERCENT, channelId)
        return ForegroundServiceStarter.startForegroundSafely(
            this,
            MigrationTransferNotificationHelper.NOTIFICATION_ID_RUNNING,
            n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onDestroy() {
        
        
        transferLocks.release()
        serviceScope.cancel()
        controlScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_RECEIVER = "com.difft.migration.p2p.ACTION_START_RECEIVER"
        const val ACTION_START_SENDER = "com.difft.migration.p2p.ACTION_START_SENDER"

        
        const val ACTION_START_REVERSE_PAIR = "com.difft.migration.p2p.ACTION_START_REVERSE_PAIR"

        
        const val ACTION_CANCEL = MigrationTransferNotificationHelper.ACTION_CANCEL

        
        private const val REQUEST_CODE_RETURN_TO_TRANSFER = 0xCAFE0104.toInt()



        const val EXTRA_ACCOUNT = "extra_account"
        const val EXTRA_QR_PAYLOAD = "extra_qr_payload"

        
        const val EXTRA_INCLUDE_ATTACHMENTS = "extra_include_attachments"

        
        const val EXTRA_SAME_DEVICE = "extra_same_device"

        




        const val EXTRA_CREDENTIAL_HANDOFF = com.difft.android.base.migration.PeerApps.EXTRA_CREDENTIAL_HANDOFF
        const val EXTRA_AWAIT_PEER_CONFIRM = com.difft.android.base.migration.PeerApps.EXTRA_AWAIT_PEER_CONFIRM

        
        const val EXTRA_DEVICE_REPLACEMENT = "extra_device_replacement"

        
        private const val TEARDOWN_SETTLE_TIMEOUT_MS = 15_000L

        
        const val PAIRING_TIMEOUT_MS = 5 * 60 * 1000L

        @VisibleForTesting
        internal const val REASON_PAIRING_TIMEOUT = "pairing timeout"

        
        internal const val PAIRING_ROTATE_INTERVAL_MS = 60 * 1000L

        
        @VisibleForTesting
        internal const val REASON_NO_RECEIVER = "reverse-pair-without-receiver"

        
        private const val INDETERMINATE = -1f

        
        private const val INDETERMINATE_PERCENT = -1

        private const val SERVICE_TAG = "MigrationTransferService"

        
        @VisibleForTesting
        internal const val REASON_NO_NOTIFICATION_CHANNEL = "no notification channel"

        
        private fun preflight(context: Context): Boolean {
            if (NotificationChannelSpecs.preflightForegroundChannel(context, SERVICE_TAG) != null) return true
            MigrationTransferStateHolder.update(TransferState.Failed(REASON_NO_NOTIFICATION_CHANNEL))
            return false
        }

        
        fun startReceiver(
            context: Context,
            account: String,
            sameDevice: Boolean = false,
            credentialHandoff: Boolean = false,
        ) {
            if (!preflight(context)) return
            val intent = Intent(context, MigrationTransferService::class.java).apply {
                action = ACTION_START_RECEIVER
                putExtra(EXTRA_ACCOUNT, account)
                putExtra(EXTRA_SAME_DEVICE, sameDevice)
                putExtra(EXTRA_CREDENTIAL_HANDOFF, credentialHandoff)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        



        fun startReversePair(context: Context, rawQrPayload: String) {
            val intent = Intent(context, MigrationTransferService::class.java).apply {
                action = ACTION_START_REVERSE_PAIR
                putExtra(EXTRA_QR_PAYLOAD, rawQrPayload)
            }
            context.startService(intent)
        }

        




        fun startSender(
            context: Context,
            rawQrPayload: String,
            includeAttachments: Boolean = true,
            credentialHandoff: Boolean = false,
            awaitPeerConfirm: Boolean = false,
            deviceReplacement: Boolean = false,
        ) {
            if (!preflight(context)) return
            val intent = Intent(context, MigrationTransferService::class.java).apply {
                action = ACTION_START_SENDER
                putExtra(EXTRA_QR_PAYLOAD, rawQrPayload)
                putExtra(EXTRA_INCLUDE_ATTACHMENTS, includeAttachments)
                putExtra(EXTRA_CREDENTIAL_HANDOFF, credentialHandoff)
                putExtra(EXTRA_AWAIT_PEER_CONFIRM, awaitPeerConfirm)
                putExtra(EXTRA_DEVICE_REPLACEMENT, deviceReplacement)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        
        fun cancel(context: Context) {
            val intent = Intent(context, MigrationTransferService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}










internal fun failureReason(e: Throwable): String = when {
    e.isPeerUnreachable() -> PrepareUploadReasons.PEER_UNREACHABLE
    e is MigrationException -> "failed: ${MigrationError.from(e).code}"
    else -> e.message ?: e.javaClass.simpleName
}








internal fun receiverFailureReason(detail: String, cause: Throwable?): String =
    if (cause != null && cause.isPeerUnreachable()) ReceiverFailureArbiter.DROPPED_LINK else detail





internal fun publishPromoting() {
    val current = MigrationTransferStateHolder.state.value
    if (current is TransferState.Transferring && current.role == TransferState.Role.RECEIVER) {
        L.i { "[MigrationV2][P2P][FGS] receive body complete → Promoting (import tail + promote)" }
        MigrationTransferStateHolder.update(TransferState.Promoting)
    }
}


internal fun isPromoting(): Boolean = MigrationTransferStateHolder.state.value == TransferState.Promoting


internal fun TransferState.acceptsSenderCallback(): Boolean =
    (this is TransferState.Transferring && role == TransferState.Role.SENDER) || this == TransferState.AwaitingPeerConfirm








internal fun Throwable.isPeerUnreachable(): Boolean =
    this is SocketException || this is SocketTimeoutException || this is UnknownHostException ||
        this is ProtocolException || this is EOFException || isTlsLinkFailure()

private fun Throwable.isTlsLinkFailure(): Boolean =
    this is SSLException && this !is SSLHandshakeException && this !is SSLPeerUnverifiedException
