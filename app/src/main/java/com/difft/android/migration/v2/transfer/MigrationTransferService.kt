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
import com.difft.android.p2p.android.server.ReceiverHttpServer
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
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the migration transfer coroutine scope, so a transfer survives
 * backgrounding instead of dying with the Activity; the view models re-attach to the live state via
 * [MigrationTransferStateHolder] on recreate.
 *
 * Invariants: this service is the single writer of [MigrationTransferStateHolder] — the view models
 * only read. `START_NOT_STICKY`, so a killed transfer is never auto-restarted; the user re-pairs.
 * Never logs the transfer key, the raw account or the raw environment.
 */
@AndroidEntryPoint
class MigrationTransferService : LifecycleService() {

    @Inject lateinit var containerWriter: ContainerWriter
    @Inject lateinit var containerReader: ContainerReader
    @Inject lateinit var backupCrypto: BackupCrypto
    @Inject lateinit var restoreOrchestrator: RestoreOrchestrator
    @Inject lateinit var environmentHelper: EnvironmentHelper
    @Inject lateinit var snapshotter: BackupSnapshotter
    @Inject lateinit var notificationHelper: MigrationTransferNotificationHelper

    /** Live logged-in account source for the send; read inside the controller, never passed as an extra. */
    @Inject lateinit var userManager: StorageBoundUserManager

    // Same-device no-login handoff collaborators. All behaviour lives in these injected classes; this
    // service only calls them before the first byte, on the receipt, on a failure and on a cancel.
    @Inject lateinit var handoffCompleter: MigrationHandoffCompleter

    /** Refreshes the linked-devices count after a Desktop cancels its own login mid-wait. */
    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var linkedDevicesCountStore: LinkedDevicesCountStore
    @Inject lateinit var handoffReconciler: MigrationHandoffReconciler
    @Inject lateinit var handoffGate: MigrationHandoffGate
    @Inject lateinit var migrationImportLauncher: MigrationImportLauncher

    /** The one send awaiting a verdict, on disk — see [PendingVerdictStore]. */
    @Inject lateinit var pendingVerdictStore: PendingVerdictStore

    /** The transfer coroutine lives here, not on the view model or controller. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * For the work that must outlive a `cancelChildren()` on [serviceScope]: asking the receiver
     * whether a cancel is still possible, and the bookkeeping around a teardown. Cancelled in onDestroy.
     */
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The sender's receiver session and what follows from it — see [SenderVerdictTracker]. */
    private val verdicts by lazy { SenderVerdictTracker(pendingVerdictStore, controlScope) }

    /** The receiver role's wait for a peer or a scan, and the code rotation (see [ReceiverPairing]). */
    private val pairing = ReceiverPairing(PAIRING_TIMEOUT_MS, PAIRING_ROTATE_INTERVAL_MS)

    /** The CPU + Wi-Fi locks a transfer holds — see [TransferLocks]. */
    private val transferLocks by lazy { TransferLocks(this) }

    /** What the tray says once the service is gone — see [TransferTerminalNotices]. */
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
                )

            ACTION_CANCEL -> cancelTransfer()

            else -> {
                L.w { "[MigrationV2][P2P][FGS] unknown action=${intent?.action} — stopping" }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ── Receiver role (the new device) ───────────────────────────────────────

    private fun startReceiverRole(account: String, sameDevice: Boolean, credentialHandoff: Boolean = false) {
        L.i { "[MigrationV2][P2P][FGS] start role=RECEIVER sameDevice=$sameDevice handoff=$credentialHandoff" }
        // Give the ongoing notification a tap target before startForeground builds it, so a transfer
        // buried by an incoming call or a Home press can always be reached again from the tray.
        notificationHelper.returnToTransferIntent = returnIntent(
            MigrationImportLauncherImpl.createP2pReceiveIntentFor(this, account, sameDevice, credentialHandoff = credentialHandoff),
        )
        // Enter the foreground first on every start path: the caller already ran
        // startForegroundService, so startForeground must be reached within 5s on every branch or
        // API 31+ throws ForegroundServiceDidNotStartInTimeException. Idempotent at the same id.
        // On a refusal the wrapper has already called stopSelf() — abort without starting a transfer.
        if (!startForegroundCompat()) {
            L.w { "[MigrationV2][P2P][FGS] startForeground refused — abort receiver" }
            return
        }
        // Guard against a second start while a transfer is in flight: it would reset the holder and
        // launch a second receiver, racing two servers on one port and two writers on the holder. The
        // in-flight transfer owns the running service, so ignore the duplicate rather than tearing down.
        if (isTransferInFlight() && !evictIdleListener()) {
            L.w { "[MigrationV2][P2P][FGS] start ignored — transfer already in flight" }
            return
        }
        MigrationTransferStateHolder.reset()
        MigrationTransferStateHolder.update(TransferState.Starting)
        pairing.resetScan()
        // Loopback never touches the radio; holding a Wi-Fi lock for it would only cost battery.
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
        receiverArbiter = ReceiverFailureArbiter(serviceScope, ::failReceiver)
        try {
            val handle = controller.startReceiver(
                scope = serviceScope, // ★ scope passed in (E1) ★
                sameDevice = sameDevice,
                onEvent = ::onReceiverServerEvent,
                onProgress = ::onReceiverProgress,
                onBodyComplete = ::publishPromoting,
                onPromoted = {
                    // Promoting is normally already published at body-complete; this is the fallback for
                    // a receive that fired no body-complete signal. Re-publishing it is a no-op.
                    MigrationTransferStateHolder.update(TransferState.Promoting)
                    MigrationTransferStateHolder.update(TransferState.Promoted)
                },
                // The database is switched but its tail is unproven: keep the data, publish no terminal
                // to the peer, and let the screen force the cold start that finishes the promote.
                onCommittedUnconfirmed = {
                    MigrationTransferStateHolder.update(TransferState.CommittedUnconfirmed)
                },
                // After the body is in, a rejection is the import's own verdict, never a peer that left.
                onRejected = { if (isPromoting()) failReceiver(it, fromOutcome = true) else failReceiverEventually(it) },
            )
            val target = handle.displayTarget
            MigrationTransferStateHolder.publishQr(target) // transferKey never logged
            MigrationTransferStateHolder.update(
                TransferState.WaitingForPeer(target.ip, target.port, target.pin),
            )
            L.i { "[MigrationV2][P2P][FGS] receiver up ip=${target.ip} port=${target.port}" }
            // The code on screen rotates while nobody has claimed it: a fresh PIN every minute, the
            // old one refused at once. Same device shows no code, so nothing rotates there.
            val rotation = if (sameDevice) null else serviceScope.launch { pairing.rotatePin(controller) }
            // Suspend until the receive outcome resolves so the FGS stays up across the whole import.
            // Bound the no-peer case so a backgrounded receiver does not stay up indefinitely. A
            // computer's export QR scanned meanwhile turns this role into reverse pairing: the listener
            // stays up — it is what the computer connects back to — and the same coroutine, locks and
            // terminal handling carry it.
            val macTarget = pairing.awaitPeerOrScan()
            rotation?.cancel()
            if (macTarget != null) {
                runReversePair(account, handle, macTarget)
            }
        } catch (e: CancellationException) {
            controller.stopReceiver()
            throw e
        } catch (e: Throwable) {
            L.e { "[MigrationV2][P2P][FGS] receiver failed: ${e.describeFailure()}" }
            MigrationTransferStateHolder.update(TransferState.Failed(failureReason(e)))
        } finally {
            receiverArbiter?.clear()
            receiverArbiter = null
            // A promoted import keeps its listener up a while longer — closed to new sessions — so a
            // sender whose final 200 was lost can still read `succeeded` from /status instead of
            // guessing. Anything else tears the server down here as before.
            if (MigrationTransferStateHolder.state.value == TransferState.Promoted) {
                controller.lingerReceiver()
            } else {
                controller.stopReceiver() // server teardown (idempotent)
            }
            finishRole()
        }
    }

    /**
     * The reverse-pairing leg of the receiver role: ask the scanned computer to connect back, then
     * hand the outcome to the ordinary upload path, exactly as the listening leg does. Runs inside
     * [runReceiver]'s try, so cancellation, teardown and the terminal notification are shared.
     */
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

    /** Routes a receiver failure through the arbiter, so a dropped link can still turn out to be the peer's cancel. */
    private fun failReceiverEventually(reason: String) {
        receiverArbiter?.onFailure(reason) ?: failReceiver(reason)
    }

    /**
     * The receiver's terminal Failed — unless the transfer already ended, or is committing: a peer that
     * goes away after the body is in changes nothing about the data already received, and a late
     * failure must not overwrite the outcome the person is looking at. The import's own verdict
     * ([fromOutcome]) always lands, because Promoting now spans the import tail and the promote.
     */
    private fun failReceiver(reason: String, fromOutcome: Boolean = false) {
        val current = MigrationTransferStateHolder.state.value
        if (!current.acceptsReceiverFailure(fromOutcome)) {
            L.w { "[MigrationV2][P2P][FGS] receiver failure after ${current::class.simpleName} ignored: $reason" }
            return
        }
        MigrationTransferStateHolder.update(TransferState.Failed(reason))
    }

    /**
     * Sender callbacks arrive from the upload's own thread, so one can land after ACTION_CANCEL has
     * already written Cancelled: a cancel closes the pipe under the uploader, which then sees a clean
     * end of body and reports it sent. Written into the holder, that late AwaitingVerdict would sit
     * there for ever — the service is gone — and every later send would be refused as "in flight".
     * Only a sender still moving bytes or waiting on its peer may advance.
     */
    private fun advanceLiveSender(next: TransferState) {
        val current = MigrationTransferStateHolder.state.value
        if (current.acceptsSenderCallback()) {
            MigrationTransferStateHolder.update(next)
        } else {
            L.w { "[MigrationV2][P2P][FGS] sender callback after ${current::class.simpleName} ignored: ${next::class.simpleName}" }
        }
    }

    /** The sender's advisory total for the receive in flight; carried on every progress update for the reading. */
    private var receiverTotalBytes = 0L

    /** Decides what a receive that stopped short is reported as; one per receiver role. */
    private var receiverArbiter: ReceiverFailureArbiter? = null

    /**
     * Approximate receiver progress, body bytes against the sender's advisory estimate, driving the
     * determinate bar on the receive screen and the notification. Guarded so a late callback cannot
     * clobber a state that has already left Transferring.
     */
    private fun onReceiverProgress(ratio: Float) {
        val current = MigrationTransferStateHolder.state.value
        if (current is TransferState.Transferring && current.role == TransferState.Role.RECEIVER) {
            MigrationTransferStateHolder.update(
                TransferState.Transferring(TransferState.Role.RECEIVER, ratio, current.peerAlias, receiverTotalBytes),
            )
            if (ratio > 0f) {
                notificationHelper.updateProgress((ratio * 100).toInt())
            }
        }
    }

    private fun onReceiverServerEvent(event: ReceiverHttpServer.ServerEvent) {
        when (event) {
            is ReceiverHttpServer.ServerEvent.IncomingPrepared -> {
                val peer = event.peer.alias.takeIf { it.isNotBlank() }
                receiverTotalBytes = event.totalBytes
                receiverArbiter?.onPrepared()
                L.i { "[MigrationV2][P2P][FGS] state → Transferring (receiver) peer=$peer totalBytes=${event.totalBytes}" }
                MigrationTransferStateHolder.update(
                    TransferState.Transferring(TransferState.Role.RECEIVER, INDETERMINATE, peerAlias = peer, totalBytes = event.totalBytes),
                )
            }
            // A gate reject (env/account/version) arrives as ServerEvent.Error carrying the typed wire
            // reason; map it to a terminal Failed so the view model can show the specific message.
            is ReceiverHttpServer.ServerEvent.Error -> {
                val reason = receiverFailureReason(event.detail, event.cause)
                L.w { "[MigrationV2][P2P][FGS] server event error=${event.detail} reason=$reason" }
                failReceiverEventually(reason)
            }
            is ReceiverHttpServer.ServerEvent.UploadStarted -> receiverArbiter?.onUploadStarted()
            is ReceiverHttpServer.ServerEvent.PeerCancelled -> {
                L.i { "[MigrationV2][P2P][FGS] peer cancelled its session" }
                receiverArbiter?.onPeerCancelled() ?: failReceiver(ReceiverFailureArbiter.PEER_LEFT)
            }
            // The 200 is written AND flushed before this event fires, so it is the signal that the
            // sender can now read its receipt. The handoff receive screen waits for it before
            // restarting the process.
            is ReceiverHttpServer.ServerEvent.FileReceived -> MigrationTransferStateHolder.markReceiptDelivered()
            is ReceiverHttpServer.ServerEvent.Claimed -> {
                L.i { "[MigrationV2][P2P][FGS] pairing code claimed by ${event.peer.alias}" }
                MigrationTransferStateHolder.markPairingClaimed()
            }
            else -> { /* Started/PeerRegistered/Stopped — not surfaced here */ }
        }
    }

    // ── Sender role (the old device) ───────────────────────────────────

    private fun startSenderRole(
        raw: String,
        includeAttachments: Boolean,
        credentialHandoff: Boolean = false,
        awaitPeerConfirm: Boolean = false,
    ) {
        L.i {
            "[MigrationV2][P2P][FGS] start role=SENDER includeAttachments=$includeAttachments " +
                "handoff=$credentialHandoff awaitPeerConfirm=$awaitPeerConfirm"
        }
        // Same tap-target wiring as the receiver role (see there).
        notificationHelper.returnToTransferIntent = returnIntent(
            MigrationImportLauncherImpl.createP2pSendIntentFor(this),
        )
        // Enter the foreground first, before the QR decode and any early exit: the caller already ran
        // startForegroundService, so an early return that stopSelf()ed without startForeground would
        // crash on API 31+. On a refusal the wrapper has already called stopSelf() — just abort.
        if (!startForegroundCompat()) {
            L.w { "[MigrationV2][P2P][FGS] startForeground refused — abort sender" }
            return
        }
        // Ignore a duplicate start while a transfer is already in flight (see startReceiverRole).
        if (isTransferInFlight() && !evictIdleListener()) {
            L.w { "[MigrationV2][P2P][FGS] start ignored — transfer already in flight" }
            return
        }
        val target: QrPayload? = QrPayloadCodec.decode(raw)
        if (target == null) {
            L.w { "[MigrationV2][P2P][FGS] sender start with undecodable QR — stopping" }
            MigrationTransferStateHolder.reset()
            MigrationTransferStateHolder.update(TransferState.Failed("invalid QR payload"))
            // Leave the foreground we just entered before stopping, on this early-exit branch.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        MigrationTransferStateHolder.reset()
        MigrationTransferStateHolder.update(TransferState.Starting)
        transferLocks.acquire()
        serviceScope.launch { runSender(target, includeAttachments, credentialHandoff, awaitPeerConfirm) }
    }

    /**
     * A transfer is in flight when the holder is neither [TransferState.Idle] nor terminal. A second
     * start in this window must be ignored: the existing transfer owns the service and the scope.
     */
    private fun isTransferInFlight(): Boolean {
        val current = MigrationTransferStateHolder.state.value
        return current != TransferState.Idle && !current.isTerminal()
    }

    /**
     * A receiver that is still only listening (no peer has connected) is not a transfer worth
     * protecting: a QR page that was backed out of, or never scanned, would otherwise block every
     * later start until its pairing timeout. A deliberate new start therefore tears it down and
     * proceeds. Anything past pairing — bytes flowing, importing, awaiting a verdict — keeps the
     * guard: two transfers must never share the holder. Returns true when the way is now clear.
     */
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
    ) {
        // Minted and persisted before any byte: if this process dies mid-transfer, the id on disk is
        // the only thing that lets the next start ask the new app whether the handoff completed.
        val handoffId = if (credentialHandoff) UUID.randomUUID().toString() else ""
        if (credentialHandoff) {
            userManager.update(commit = true) { migrationHandoffId = handoffId }
        }
        try {
            MigrationTransferStateHolder.update(
                TransferState.Transferring(TransferState.Role.SENDER, INDETERMINATE),
            )
            // Settle which of the advertised addresses to talk to before anything else; the address
            // that wins is the one every later request, the session record and a resumed verdict
            // query use. A single-address code passes straight through. All candidates failing is an
            // unreachable peer, reported through the same path as a refused connection.
            val target = EndpointRace.resolve(target)
                ?: throw java.net.SocketException("none of the ${target.candidates.size} advertised addresses answered")
            val onProgress: (Float) -> Unit = { ratio ->
                advanceLiveSender(TransferState.Transferring(TransferState.Role.SENDER, ratio))
                // The percent is known only for the Content-Length send. The chunked real-export path
                // reports ratio=0f, so keep the bar indeterminate there rather than pinning it to 0%.
                if (ratio > 0f) {
                    notificationHelper.updateProgress((ratio * 100).toInt())
                }
            }
            val onBodySent: () -> Unit = {
                L.i { "[MigrationV2][P2P][FGS] body sent — awaiting the receiver's verdict" }
                advanceLiveSender(TransferState.AwaitingVerdict)
                verdicts.onBodySent()
            }
            verdicts.reset()
            // The handoff's receipt is the 200 plus the receiver's own word that it installed the
            // credential; a 200 alone is what a receiver that never understood the handoff returns.
            var handoffAcknowledged = false
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
                onSessionPrepared = verdicts::onPrepared,
                verdictWatch = VerdictWatch(),
                onVerdictStall = { stalled ->
                    advanceVerdictWait(if (stalled) TransferState.VerdictStalled else TransferState.AwaitingVerdict)
                },
            )
            val handoffInstalled = credentialHandoff && ok && handoffAcknowledged
            MigrationTransferStateHolder.update(
                when {
                    !ok -> TransferState.Failed("upload not confirmed (no 200)")
                    credentialHandoff && !handoffAcknowledged -> TransferState.Failed(HANDOFF_NOT_ACKNOWLEDGED)
                    else -> TransferState.Sent
                },
            )
            verdicts.settled() // confirmed or refused: the outcome is known
            if (credentialHandoff) {
                if (handoffInstalled) {
                    // The receipt: the new app promoted the database, installed the identity and said it
                    // installed the credential. Only now may this device stop being a live session, and
                    // the process dies inside complete(), so no terminal notification follows.
                    handoffCompleter.complete(handoffId)
                    return
                }
                if (ok) L.w { "[MigrationV2][P2P][FGS] handoff answered 200 without the installed acknowledgment — keeping this session" }
                // No receipt. The new app may still have finished (a 200 lost on the way back, or an
                // older new app that does not acknowledge), so ask it directly now rather than leaving
                // two live sessions until the next cold start. The failure screen's retry stays gated
                // on the answer.
                reconcileAfterHandoffFailure()
            }
            // Outside the handoff path this device does not sign itself out. A phone receiver registers
            // as the primary device and the server revokes this one, which SessionAuthGuard handles on
            // any screen; a desktop links as a secondary and this device stays the valid primary.
        } catch (e: CancellationException) {
            // A cancel means the body never finished, so the new app provably did not complete —
            // clear the id synchronously instead of leaving a stale one for the next cold start to
            // chase. Nothing was wiped here; this device is still online and still signed in.
            if (credentialHandoff) {
                userManager.update(commit = true) { migrationHandoffId = "" }
            }
            throw e
        } catch (e: SendVerdictException) {
            // The receiver's own word on a connection of its own, or a wait that ran out — not a
            // transport failure, and never followed by a cancel.
            MigrationTransferStateHolder.update(verdicts.stateFor(e.verdict))
            if (credentialHandoff) reconcileAfterHandoffFailure()
        } catch (e: Throwable) {
            L.e { "[MigrationV2][P2P][FGS] sender failed: ${e.describeFailure()}" }
            MigrationTransferStateHolder.update(TransferState.Failed(failureReason(e)))
            verdicts.settled()
            if (credentialHandoff) reconcileAfterHandoffFailure()
            if ((e as? PrepareUploadRejectedException)?.reason == PrepareUploadReasons.PEER_CANCELLED) refreshLinkedDevices()
        } finally {
            finishRole()
        }
    }

    /**
     * The user's cancel. While the sender is only waiting for the verdict, the receiver is asked
     * first: past its commit point a cancel changes nothing, and tearing this side down would only
     * make the outcome unknowable. Everywhere else the transfer is torn down at once, as before.
     */
    private fun cancelTransfer() {
        if (!verdicts.mustAskReceiver(MigrationTransferStateHolder.state.value)) {
            L.i { "[MigrationV2][P2P][FGS] ACTION_CANCEL — tearing down transfer" }
            tearDown(TransferState.Cancelled)
            return
        }
        L.i { "[MigrationV2][P2P][FGS] ACTION_CANCEL while awaiting the verdict — asking the receiver" }
        controlScope.launch {
            val decision = verdicts.askCancel()
            if (MigrationTransferStateHolder.state.value.isTerminal()) return@launch // decided meanwhile
            when (decision) {
                SenderVerdictTracker.CancelDecision.KEEP_WAITING -> MigrationTransferStateHolder.notify(TransferNotice.CANCEL_TOO_LATE)
                SenderVerdictTracker.CancelDecision.CANCELLED -> tearDown(TransferState.Cancelled)
                SenderVerdictTracker.CancelDecision.UNKNOWN -> tearDown(TransferState.Unknown)
            }
        }
    }

    /** Ends the role from outside its coroutine: state first, then the work, then the foreground. */
    private fun tearDown(terminal: TransferState) {
        MigrationTransferStateHolder.update(terminal)
        serviceScope.coroutineContext.cancelChildren()
        if (terminal == TransferState.Unknown) postTerminalNotification() else controlScope.launch { verdicts.settled() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Only a sender still waiting for the verdict may be told the receiver stalled or resumed. */
    private fun advanceVerdictWait(next: TransferState) {
        if (MigrationTransferStateHolder.state.value.isAwaitingVerdict()) MigrationTransferStateHolder.update(next)
    }

    /**
     * The role's last act: report the outcome and leave the foreground. A role whose coroutine was
     * cancelled does not own the service any more — an evicted idle listener handed it to the role
     * that evicted it, and ACTION_CANCEL / onDestroy have already stopped it — so stopping here
     * would tear down a transfer that is not this one.
     */
    private suspend fun finishRole() {
        if (!currentCoroutineContext().isActive) {
            L.i { "[MigrationV2][P2P][FGS] role cancelled — the service stays with its owner" }
            return
        }
        postTerminalNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Ask the new app, in-session, whether it nonetheless finished — the failed-send case where this
     * process is still alive. A COMPLETED answer signs this device out from inside the reconciler;
     * anything else leaves it online. Never throws: a reconcile failure must not mask the real
     * transfer failure the user is about to see.
     */
    /**
     * The Desktop unlinked itself on the server when its user cancelled the account switch, so the
     * count this device shows is stale until re-read. Best effort; the list screen re-reads anyway.
     */
    private suspend fun refreshLinkedDevices() {
        runCatching { deviceRepository.getDevices() }
            .onSuccess { linkedDevicesCountStore.update(it.size) }
            .onFailure { L.w { "[MigrationV2][P2P][FGS] linked-devices refresh after peer cancel failed: ${it.message}" } }
    }

    private suspend fun reconcileAfterHandoffFailure() {
        runCatching { handoffReconciler.onSendFailure() }
            .onFailure { L.w { "[MigrationV2][P2P][FGS] handoff reconcile failed: ${it.message}" } }
    }


    /** An unreachable peer is a one-liner; anything else keeps its stack. */
    private fun Throwable.describeFailure(): String =
        if (isPeerUnreachable()) "could not reach the peer: $message" else stackTraceToString()

    private fun postTerminalNotification() = terminalNotices.post(MigrationTransferStateHolder.state.value)

    @VisibleForTesting
    internal fun localizedFailureReason(reason: String): String = terminalNotices.localizedFailureReason(reason)

    // ── FGS plumbing ───────────────────────────────────────────────────────

    /**
     * Promotes to the foreground. Returns false if the OS refused (ForegroundServiceStartNotAllowed
     * on Android 12+): the wrapper has already called stopSelf(), so callers must not continue.
     */
    /**
     * Wrap a transfer-screen intent as the ongoing notification's tap target. `SINGLE_TOP | CLEAR_TOP`
     * reuses the live Activity when it is still in the task (so a receive keeps its for-result link to
     * the login flow) instead of stacking a second copy; `FLAG_IMMUTABLE` is required on API 31+.
     */
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
        val n = notificationHelper.buildRunning(INDETERMINATE_PERCENT)
        return ForegroundServiceStarter.startForegroundSafely(
            this,
            MigrationTransferNotificationHelper.NOTIFICATION_ID_RUNNING,
            n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onDestroy() {
        // Locks first: onDestroy is the one path every teardown reaches (cancel action, terminal
        // stopSelf, system kill), so releasing here cannot leak them.
        transferLocks.release()
        serviceScope.cancel()
        controlScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_RECEIVER = "com.difft.migration.p2p.ACTION_START_RECEIVER"
        const val ACTION_START_SENDER = "com.difft.migration.p2p.ACTION_START_SENDER"

        /** Receiver role only: reverse-pair with the computer whose export QR rides in [EXTRA_QR_PAYLOAD]. */
        const val ACTION_START_REVERSE_PAIR = "com.difft.migration.p2p.ACTION_START_REVERSE_PAIR"

        /** Cancel action; keep in sync with [MigrationTransferNotificationHelper.ACTION_CANCEL]. */
        const val ACTION_CANCEL = MigrationTransferNotificationHelper.ACTION_CANCEL

        /** Distinct from the helper's cancel request code so the two PendingIntents never alias. */
        private const val REQUEST_CODE_RETURN_TO_TRANSFER = 0xCAFE0104.toInt()



        const val EXTRA_ACCOUNT = "extra_account"
        const val EXTRA_QR_PAYLOAD = "extra_qr_payload"

        /** Whether attachment bytes travel (voice notes always do). Absent → true. */
        const val EXTRA_INCLUDE_ATTACHMENTS = "extra_include_attachments"

        /** Receiver role: listen on loopback for the old app on this device (no QR, no LAN). */
        const val EXTRA_SAME_DEVICE = "extra_same_device"

        /**
         * Both roles: this transfer is the same-device no-login credential handoff. Receiver → the
         * container is expected to carry a session credential and this install adopts it; sender →
         * the credential is projected into the container and this install signs out on the receipt.
         */
        const val EXTRA_CREDENTIAL_HANDOFF = com.difft.android.base.migration.PeerApps.EXTRA_CREDENTIAL_HANDOFF
        const val EXTRA_AWAIT_PEER_CONFIRM = com.difft.android.base.migration.PeerApps.EXTRA_AWAIT_PEER_CONFIRM

        /** Pairing timeout, matching the PIN TTL. Bounds how long a peerless receiver stays up. */
        const val PAIRING_TIMEOUT_MS = 5 * 60 * 1000L

        @VisibleForTesting
        internal const val REASON_PAIRING_TIMEOUT = "pairing timeout"

        /** How long one pairing code stays on screen before the listener issues the next. */
        internal const val PAIRING_ROTATE_INTERVAL_MS = 60 * 1000L

        /** Reverse pairing was requested while no receiver role was up to carry it. */
        @VisibleForTesting
        internal const val REASON_NO_RECEIVER = "reverse-pair-without-receiver"

        /** Indeterminate progress signal carried in [TransferState.Transferring]. */
        private const val INDETERMINATE = -1f

        /** Indeterminate percent passed to the notification (negative → indeterminate bar). */
        private const val INDETERMINATE_PERCENT = -1

        /** Starts the receiver role (the new device) from any context. */
        fun startReceiver(
            context: Context,
            account: String,
            sameDevice: Boolean = false,
            credentialHandoff: Boolean = false,
        ) {
            val intent = Intent(context, MigrationTransferService::class.java).apply {
                action = ACTION_START_RECEIVER
                putExtra(EXTRA_ACCOUNT, account)
                putExtra(EXTRA_SAME_DEVICE, sameDevice)
                putExtra(EXTRA_CREDENTIAL_HANDOFF, credentialHandoff)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Turns the waiting receiver role into reverse pairing with the computer whose export QR was
         * just scanned. Ignored when no receiver is waiting.
         */
        fun startReversePair(context: Context, rawQrPayload: String) {
            val intent = Intent(context, MigrationTransferService::class.java).apply {
                action = ACTION_START_REVERSE_PAIR
                putExtra(EXTRA_QR_PAYLOAD, rawQrPayload)
            }
            context.startService(intent)
        }

        /**
         * Starts the sender role (the old device) from any context. [rawQrPayload] is the scanned QR;
         * the transfer key rides inside it and is decoded in the controller. No account, environment
         * or transfer-key extra is passed — identity is read live inside the controller.
         */
        fun startSender(
            context: Context,
            rawQrPayload: String,
            includeAttachments: Boolean = true,
            credentialHandoff: Boolean = false,
            awaitPeerConfirm: Boolean = false,
        ) {
            val intent = Intent(context, MigrationTransferService::class.java).apply {
                action = ACTION_START_SENDER
                putExtra(EXTRA_QR_PAYLOAD, rawQrPayload)
                putExtra(EXTRA_INCLUDE_ATTACHMENTS, includeAttachments)
                putExtra(EXTRA_CREDENTIAL_HANDOFF, credentialHandoff)
                putExtra(EXTRA_AWAIT_PEER_CONFIRM, awaitPeerConfirm)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Request cancellation of an in-flight transfer. Safe no-op if nothing is running. */
        fun cancel(context: Context) {
            val intent = Intent(context, MigrationTransferService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}

/**
 * The role's terminal reason for [e], in the vocabulary `classifyFailure` reads. A peer that cannot
 * be reached — nobody listening at the pairing address (a Desktop that gave up waiting stops its
 * receiver), no route or an unresolvable host (a VPN or a second network carrying this device's
 * traffic away from the LAN), a connect that timed out, or a link that dropped mid-transfer — is
 * its own wire reason, kept apart from a stale-PIN 401 so the page says "connection", not
 * "unexpected error". A migration failure carries its error code, the way the receive side's
 * RestoreResult already does; anything else keeps its message (wire reject tokens live there).
 */
internal fun failureReason(e: Throwable): String = when {
    e.isPeerUnreachable() -> PrepareUploadReasons.PEER_UNREACHABLE
    e is MigrationException -> "failed: ${MigrationError.from(e).code}"
    else -> e.message ?: e.javaClass.simpleName
}

/**
 * The receiver's reason for a connection that failed under its server. A socket that ended under
 * our read — the sender hung up, even before its first byte was flushed — or a read that timed out
 * is the link going, the same verdict the reader gives a stream that ends early. Whether the sender
 * left on purpose is what its `/cancel` says, not the shape of the drop. Anything else (a gate
 * reject, a protocol error) keeps its detail, which carries the wire token the classifier reads.
 */
internal fun receiverFailureReason(detail: String, cause: Throwable?): String =
    if (cause != null && cause.isPeerUnreachable()) ReceiverFailureArbiter.DROPPED_LINK else detail

/**
 * Everything is in; the import tail and promote run now. Only a live Transferring may advance, so a
 * transfer that already ended — or one the fallback in `onPromoted` moved on — is left alone.
 */
internal fun publishPromoting() {
    val current = MigrationTransferStateHolder.state.value
    if (current is TransferState.Transferring && current.role == TransferState.Role.RECEIVER) {
        L.i { "[MigrationV2][P2P][FGS] receive body complete → Promoting (import tail + promote)" }
        MigrationTransferStateHolder.update(TransferState.Promoting)
    }
}

/** The receiver is past its body and into the import tail: a rejection now is the import's own verdict. */
internal fun isPromoting(): Boolean = MigrationTransferStateHolder.state.value == TransferState.Promoting

/** A sender state that a progress / body-sent / peer-confirm callback may still advance. */
internal fun TransferState.acceptsSenderCallback(): Boolean =
    (this is TransferState.Transferring && role == TransferState.Role.SENDER) || this == TransferState.AwaitingPeerConfirm

/**
 * The peer or the link is gone: refused, reset, no route, unresolvable, timed out, or the stream
 * ended before its end. Subclasses cover connect refusals and resets (SocketException) and the
 * "unexpected end of stream" OkHttp raises when the other side hangs up mid-body. Over TLS the same
 * hang-up surfaces as an SSLException on the read or write; the handshake refusing the peer (a pin
 * mismatch) is not that and keeps its own reason.
 */
internal fun Throwable.isPeerUnreachable(): Boolean =
    this is SocketException || this is SocketTimeoutException || this is UnknownHostException ||
        this is ProtocolException || this is EOFException || isTlsLinkFailure()

private fun Throwable.isTlsLinkFailure(): Boolean =
    this is SSLException && this !is SSLHandshakeException && this !is SSLPeerUnverifiedException
