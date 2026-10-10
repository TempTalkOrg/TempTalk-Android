package com.difft.android.migration.v2.transfer.login

import android.content.Context
import android.graphics.Bitmap
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.restore.RestoreOrchestrator
import com.difft.android.migration.v2.restore.RestorePaths
import com.difft.android.migration.v2.restore.RestoreResult
import com.difft.android.migration.v2.restore.StorageGuard
import com.difft.android.migration.v2.handoff.MigrationHandoffGate
import com.difft.android.migration.v2.transfer.MigrationEnvironment
import com.difft.android.migration.v2.transfer.MigrationHandshakeGate
import com.difft.android.migration.v2.transfer.P2pContainerFusion
import com.difft.android.migration.v2.transfer.ReceiverLinger
import com.difft.android.p2p.android.NetworkUtils
import com.difft.android.p2p.android.qr.QrPayloadCodec
import com.difft.android.p2p.android.server.FileTerminalRecordStore
import com.difft.android.p2p.android.server.ReceiverHttpServer
import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.session.PairingSessionRegistry
import com.difft.android.p2p.engine.upload.StoragePrecheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import com.difft.android.migration.v2.transfer.DeviceNames
import com.difft.android.base.migration.PeerApps

/**
 * The receive driver for the login-triggered migration: stands the receiver up with the pre-transfer
 * gate wired, advertises this device's identity in the QR, and routes the upload body into
 * [RestoreOrchestrator.restoreFromStream], returning [RestoreResult.ReadyForSignIn] on a verified and
 * promoted import for the `:login` sign-in tail to consume.
 *
 * It runs before sign-in, so [loggingInAccount] is the just-verified account from `:login`; the same
 * value is advertised in the QR, required by the gate and matched against the manifest. The ephemeral
 * transfer key is generated inside the fusion and never surfaced or logged here.
 */
/**
 * Failure token raised when the receiver has no LAN address to advertise. Travels in the transfer
 * state's reason string like the handshake reject tokens, so the screen can show an actionable
 * message ("join the same Wi-Fi") instead of the generic failure.
 */
internal const val NO_LAN_REASON = "no-lan"

class MigrationP2pReceiveController(
    context: Context,
    writer: ContainerWriter,
    reader: ContainerReader,
    crypto: BackupCrypto,
    private val orchestrator: RestoreOrchestrator,
    private val environmentHelper: EnvironmentHelper,
    private val appVersion: String,
    private val loggingInAccount: String,
    /**
     * Same-device no-login handoff: this receiver has no account and expects the container to carry
     * the sender's session credential. Needs [handoffGate] and [isFreshTarget] so the gate can
     * re-check both the handoff capability and the net-install rule at handshake time.
     */
    private val credentialHandoff: Boolean = false,
    private val handoffGate: MigrationHandoffGate? = null,
    private val isFreshTarget: () -> Boolean = { true },
) {

    private val appContext = context.applicationContext
    private val fusion = P2pContainerFusion(writer = writer, reader = reader, crypto = crypto)
    private val certStorage = CertStorage(File(context.filesDir, "p2p"))
    /** Same private dir as the certificate; deliberately not the chat database, which a promote replaces. */
    private val terminalStore = FileTerminalRecordStore(File(context.filesDir, "p2p"))

    private var session: P2pContainerFusion.ReceiverSession<RestoreResult>? = null

    /**
     * The account this receiver adopted from the sender's handshake, in handoff mode only. Written
     * once by the gate's accept callback and read once when the body starts; the gate runs strictly
     * before the body, so a single reference needs no lock.
     */
    private val adoptedAccount = AtomicReference("")

    /**
     * The QR to render; it carries this device's identity and the transfer key, which is never logged.
     * [pairingRegistry] is the live listener's reverse-pairing seam: the caller registers a scanned
     * computer's PIN and transfer key there before asking it to connect back.
     */
    data class ReceiverHandle(
        val qrBitmap: Bitmap?,
        val displayTarget: QrPayload,
        val pairingRegistry: PairingSessionRegistry,
    )

    /**
     * Stands the gated receiver up and observes the import outcome: [onPromoted] on a verified and
     * promoted import, [onCommittedUnconfirmed] when the database was switched but the tail after it
     * is not proven done, [onRejected] on a gate or guard rejection, tampering or a failure.
     *
     * [scope] is the service's own scope, so Activity death does not cancel an in-flight import. This
     * controller owns no scope; [stopReceiver] tears down only the server session.
     */
    suspend fun startReceiver(
        scope: CoroutineScope,
        onEvent: (ReceiverHttpServer.ServerEvent) -> Unit,
        onPromoted: () -> Unit,
        // The import is committed but its tail is unproven: the data is kept, no terminal is published
        // to the peer, and this device must cold-start so the promote finishes before any sign-in.
        onCommittedUnconfirmed: () -> Unit,
        onRejected: (String) -> Unit,
        // Approximate receive progress in [0,1], body bytes against the sender's advisory estimate. The
        // service maps it to TransferState.Transferring so the receive screen drives a determinate bar.
        onProgress: (Float) -> Unit = {},
        // The whole body is in; only the import's verdict is outstanding. The service turns this into
        // the verifying page, so the import tail and the promote are not shown as a frozen 100%.
        onBodyComplete: () -> Unit = {},
        // Same device: listen on loopback — no LAN needed, the old app on this device connects locally.
        sameDevice: Boolean = false,
    ): ReceiverHandle = withContext(Dispatchers.IO) {
        stopReceiver() // idempotent re-entry
        ReceiverLinger.shared.stopNow() // a listener left up after the last transfer holds the port

        // No LAN address means no peer can reach us, so report it here rather than handing the user a
        // QR that looks fine and can never connect: the entry is offered regardless of reachability.
        // Otherwise advertise every LAN address, best first, and let the old device race them, so a
        // phone with Wi-Fi and its hotspot both up does not have to guess which one the peer is on.
        val candidates = if (sameDevice) {
            listOf(PeerApps.LOOPBACK_IP)
        } else {
            NetworkUtils.lanCandidates().ifEmpty { throw IllegalStateException(NO_LAN_REASON) }
        }
        val ip = candidates.first()
        val cert = SelfSignedCert.loadOrCreate(certStorage, ip)

        // A process kill mid-receive leaves segment files nobody owns, so the spool starts from empty.
        // Best-effort: a delete failure must not stop a receive that would otherwise work.
        val paths = RestorePaths.forContext(appContext)
        val spoolDir = File(paths.spoolDir)
        runCatching { spoolDir.deleteRecursively() }
            .onFailure { e -> L.w { "[MigrationV2][P2P][Rx] stale spool wipe failed: ${e.javaClass.simpleName}" } }

        val storageGuard = StorageGuard.forContext(appContext)
        val storagePrecheck = StoragePrecheck { declaredBytes ->
            val verdict = storageGuard.precheck(declaredBytes)
            if (!verdict.sufficient) {
                L.w {
                    "[MigrationV2][Restore] prepare-upload refused: " +
                        "need=${verdict.requiredBytes / BYTES_PER_MB}MB free=${verdict.availableBytes / BYTES_PER_MB}MB"
                }
            }
            verdict.sufficient
        }

        // This device's advertised identity for the QR, plus the authoritative gate at prepare-upload,
        // which fails fast when the old device's handshake does not match this device.
        val identity = P2pContainerFusion.ReceiverIdentity(
            environment = MigrationEnvironment.wireName(environmentHelper),
            account = loggingInAccount,
            appVersion = appVersion,
            formatVersion = FormatCompat.SUPPORTED_FORMAT_VERSION,
        )
        adoptedAccount.set("")
        val gate = MigrationHandshakeGate(
            environmentHelper = environmentHelper,
            loggingInAccount = loggingInAccount,
            credentialHandoff = credentialHandoff,
            isFreshTarget = isFreshTarget,
            handoffEnabled = { handoffGate != null },
            // The adopted account is stored, never logged.
            onAccept = { adoptedAccount.set(it.sourceAccount) },
        )

        val s = fusion.startRestoreReceiver(
            ip = ip,
            ips = candidates,
            scope = scope,
            cert = cert,
            alias = DeviceNames.local(appContext),
            orchestrator = orchestrator,
            currentVerifyAccount = loggingInAccount,
            onEvent = onEvent,
            requirePin = true,
            identity = identity,
            migrationValidator = gate,
            onProgress = onProgress,
            onBodyComplete = onBodyComplete,
            credentialHandoffAccount = if (credentialHandoff) adoptedAccount::get else null,
            terminalStore = terminalStore,
            spoolDir = spoolDir,
            // Runs on the socket thread every 8 MiB and at each segment seal; throwing stops the
            // receive with a typed out-of-space failure instead of an opaque ENOSPC later on.
            onWriteProgress = { _, _ -> storageGuard.requireHeadroom("spool") },
            storagePrecheck = storagePrecheck,
        )
        session = s

        scope.launch {
            runCatching { s.awaitOutcome() }
                .onSuccess { result ->
                    when (result) {
                        is RestoreResult.ReadyForSignIn -> {
                            L.i { "[MigrationV2][P2P][Rx] promoted → ReadyForSignIn (returning to login)" }
                            onPromoted()
                        }
                        is RestoreResult.Rejected -> onRejected("rejected: ${result.error.code}")
                        is RestoreResult.Failed -> onRejected("failed: ${result.error.code}")
                    }
                }
                .onFailure { e ->
                    // A CancellationException here is a scope cancel, not a transfer failure. Routing it to
                    // onRejected would write Failed after the service already wrote Cancelled and post a
                    // spurious "transfer failed" notification. Rethrow so a cancel stays Cancelled.
                    if (e is CancellationException) throw e
                    // Committed but unconfirmed is neither a success nor a failure: onPromoted() would
                    // start an in-process sign-in against a database whose promote tail is not proven
                    // done, and onRejected() would claim a failure that did not happen.
                    if (e is MigrationException.CommittedUnconfirmed) {
                        L.w { "[MigrationV2][P2P][Rx] import committed; tail unconfirmed — cold start to finish it" }
                        onCommittedUnconfirmed()
                        return@onFailure
                    }
                    onRejected(e.message ?: e.javaClass.simpleName)
                }
        }

        // Render the QR without exposing the transfer key on screen: only ip, port, pin and identity.
        ReceiverHandle(QrPayloadCodec.encode(s.qrPayload), s.qrPayload, s.pairingRegistry)
    }

    /**
     * Stops the server session: the HTTP listener and the receive sink. Idempotent, and it cancels no
     * coroutine scope — the service owns the scope and cancels its own children.
     */
    fun stopReceiver() {
        session?.stop()
        session = null
    }

    /**
     * Leave the server up, closed to new sessions, so the sender can still ask `/status` how this
     * transfer ended after its final response — for as long as [ReceiverLinger] allows, and never
     * beyond the process. The user is not held anywhere for this; it happens behind the sign-in.
     */
    fun lingerReceiver() {
        session?.let { ReceiverLinger.shared.keep(it) }
        session = null
    }

    /** Issue a fresh pairing PIN on the live listener; null when nothing is listening. */
    fun rotatePin(): QrPayload? = session?.rotatePin()

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
