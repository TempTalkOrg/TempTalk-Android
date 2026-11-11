package com.difft.android.migration.v2.transfer

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.v2.container.AttachmentByteTarget
import com.difft.android.migration.v2.container.ContainerManifest
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.container.counting
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.restore.RestoreOrchestrator
import com.difft.android.migration.v2.restore.RestoreResult
import com.difft.android.p2p.android.server.ReceiverHttpServer
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.p2p.engine.session.PairingSessionRegistry
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import com.difft.android.p2p.engine.session.TerminalRecordStore
import com.difft.android.p2p.engine.session.TransferProgressReporter
import com.difft.android.p2p.engine.upload.MigrationHandshakeValidator
import com.difft.android.p2p.engine.upload.StoragePrecheck
import com.difft.android.p2p.engine.upload.UploadReplyHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetAddress











class P2pContainerFusion(
    private val writer: ContainerWriter,
    private val reader: ContainerReader,
    private val crypto: BackupCrypto,
) {

    
    private val sender = P2pSenderPipeline(writer)

    




    class ReceiverSession<R> internal constructor(
        qrPayload: QrPayload,
        val transferKey: String,
        private val server: ReceiverHttpServer,
        private val receiveSink: P2pContainerReceiveSink<R>,
        private val pinPolicy: PinPolicy,
        



        val pairingRegistry: PairingSessionRegistry,
    ) : ReceiverLinger.Listener {
        
        @Volatile
        var qrPayload: QrPayload = qrPayload
            private set

        
        fun rotatePin(): QrPayload {
            val next = qrPayload.copy(pin = pinPolicy.issue())
            qrPayload = next
            return next
        }

        
        suspend fun awaitOutcome(): R = receiveSink.result.await()

        
        override fun closeToNewSessions() {
            server.closeToNewSessions()
        }

        



        fun retireForLinger(): Boolean {
            val hadSession = server.retireLiveSessionForLinger()
            runCatching { receiveSink.abort() }
            return hadSession
        }

        




        override fun stop() {
            runCatching { server.stop() }
            runCatching { receiveSink.abort() }
        }
    }

    









    fun startReceiver(
        ip: String,
        scope: CoroutineScope,
        cert: SelfSignedCert,
        alias: String,
        spoolDir: File,
        attachmentTarget: AttachmentByteTarget,
        onRecord: suspend (NeutralRecord) -> Unit,
        onEvent: (ReceiverHttpServer.ServerEvent) -> Unit = {},
        port: Int = ReceiverHttpServer.DEFAULT_PORT,
        requirePin: Boolean = true,
    ): ReceiverSession<ContainerManifest> = bringUpReceiver(
        ip = ip,
        scope = scope,
        cert = cert,
        alias = alias,
        onEvent = onEvent,
        port = port,
        requirePin = requirePin,
        identity = null,            
        migrationValidator = null,
    ) { transferKey, _, _ ->
        
        P2pContainerReceiveSink<ContainerManifest>(scope = scope, spoolDir = spoolDir) { input ->
            reader.readStreaming(input, transferKey, attachmentTarget, onRecord = onRecord)
        }
    }

    








    fun startRestoreReceiver(
        ip: String,
        scope: CoroutineScope,
        cert: SelfSignedCert,
        alias: String,
        orchestrator: RestoreOrchestrator,
        currentVerifyAccount: String,
        
        
        spoolDir: File,
        onEvent: (ReceiverHttpServer.ServerEvent) -> Unit = {},
        port: Int = ReceiverHttpServer.DEFAULT_PORT,
        requirePin: Boolean = true,
        
        
        
        identity: ReceiverIdentity? = null,
        migrationValidator: MigrationHandshakeValidator? = null,
        
        
        onProgress: (Float) -> Unit = {},
        
        
        onBodyComplete: () -> Unit = {},
        
        
        
        credentialHandoffAccount: (() -> String)? = null,
        
        
        terminalStore: TerminalRecordStore? = null,
        
        ips: List<String> = listOf(ip),
        
        
        onWriteProgress: (Long, Long) -> Unit = { _, _ -> },
        
        storagePrecheck: StoragePrecheck? = null,
    ): ReceiverSession<RestoreResult> = bringUpReceiver(
        ip = ip,
        ips = ips,
        scope = scope,
        cert = cert,
        alias = alias,
        onEvent = onEvent,
        port = port,
        requirePin = requirePin,
        identity = identity,
        migrationValidator = migrationValidator,
        terminalStore = terminalStore,
        storagePrecheck = storagePrecheck,
        caps = RESTORE_RECEIVER_CAPS,
    ) { transferKey, liveness, pairingRegistry ->
        
        
        
        
        val manifestProgressSeen = AtomicBoolean(false)
        P2pContainerReceiveSink<RestoreResult>(
            scope = scope,
            spoolDir = spoolDir,
            onWriteProgress = onWriteProgress,
            onProgress = { ratio -> if (!manifestProgressSeen.get()) onProgress(ratio) },
            
            isSuccess = { it is RestoreResult.ReadyForSignIn },
            
            
            successHeaders = { outcome ->
                if (credentialHandoffAccount != null && outcome is RestoreResult.ReadyForSignIn) {
                    mapOf(UploadReplyHeaders.HANDOFF_INSTALLED to "true")
                } else {
                    emptyMap()
                }
            },
            onBodyComplete = onBodyComplete,
        ) { input ->
            
            val account = credentialHandoffAccount?.invoke()?.takeIf { it.isNotBlank() } ?: currentVerifyAccount
            
            
            
            
            
            val key = pairingRegistry.acceptedTransferKey() ?: transferKey
            orchestrator.restoreFromStream(
                source = input,
                transferKey = key,
                currentVerifyAccount = account,
                onProgress = { ratio ->
                    manifestProgressSeen.set(true)
                    onProgress(ratio)
                },
                credentialHandoff = credentialHandoffAccount != null,
                liveness = liveness,
            )
        }
    }

    




    private fun <R> bringUpReceiver(
        ip: String,
        scope: CoroutineScope,
        cert: SelfSignedCert,
        alias: String,
        onEvent: (ReceiverHttpServer.ServerEvent) -> Unit,
        port: Int,
        requirePin: Boolean,
        identity: ReceiverIdentity?,
        migrationValidator: MigrationHandshakeValidator?,
        terminalStore: TerminalRecordStore? = null,
        storagePrecheck: StoragePrecheck? = null,
        caps: List<String> = ReceiverCaps.BASE,
        
        ips: List<String> = listOf(ip),
        sinkFactory: (
            transferKey: String,
            liveness: TransferProgressReporter,
            pairingRegistry: PairingSessionRegistry,
        ) -> P2pContainerReceiveSink<R>,
    ): ReceiverSession<R> {
        val transferKey = generateTransferKey()
        val pinPolicy = PinPolicy()
        val pinCode = pinPolicy.issue()
        
        
        val pairingRegistry = PairingSessionRegistry()

        
        
        val sessionManager = SessionManager(terminalStore = terminalStore)
        val receiveSink = sinkFactory(transferKey, sessionManager, pairingRegistry)

        val deviceInfo = DeviceInfo(alias = alias, fingerprint = cert.sha256Fingerprint, port = port)
        val server = ReceiverHttpServer(
            cert = cert,
            deviceInfo = deviceInfo,
            sessionManager = sessionManager,
            pinPolicy = pinPolicy,
            fileSink = receiveSink,
            requirePin = requirePin,
            onEvent = onEvent,
            
            migrationValidator = migrationValidator,
            pairingRegistry = pairingRegistry,
            storagePrecheck = storagePrecheck,
            caps = caps,
        )
        
        
        
        
        val loopbackOnly = ips.isNotEmpty() && ips.all { it == PeerApps.LOOPBACK_IP }
        val boundPort = server.start(port, if (loopbackOnly) InetAddress.getByName(PeerApps.LOOPBACK_IP) else null)

        val qr = QrPayload(
            ip = ip,
            ips = ips,
            port = boundPort,
            fp = cert.sha256Fingerprint,
            pin = pinCode,
            alias = alias,
            transferKey = transferKey, 
            
            
            environment = identity?.environment,
            account = identity?.account,
            appVersion = identity?.appVersion,
            formatVersion = identity?.formatVersion,
        )
        L.i { "[MigrationV2][P2P][Rx] receiver up ip=$ip port=$boundPort requirePin=$requirePin gate=${migrationValidator != null}" }
        return ReceiverSession(qr, transferKey, server, receiveSink, pinPolicy, pairingRegistry)
    }

    




    data class ReceiverIdentity(
        val environment: String,
        val account: String,
        val appVersion: String,
        val formatVersion: String,
    )

    






    suspend fun sendContainer(
        target: QrPayload,
        scope: CoroutineScope,
        records: List<NeutralRecord>,
        attachmentSource: com.difft.android.migration.v2.container.AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        onProgress: (Float) -> Unit = {},
        verdictWatch: VerdictWatch? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val transferKey = target.transferKey
            ?: error("QR has no transferKey — not a migration receiver")

        
        
        val length = sender.countContainerLength(records, attachmentSource, transferKey, sourceAccount, sourceAppVersion)
        L.i { "[MigrationV2][P2P][Tx] container length=$length records=${records.size} framing=content-length" }

        sender.streamThroughPipe(
            target = target,
            transferKey = transferKey,
            declaredLength = length,
            
            estimatedTotalBytes = 0L,
            records = records.asFlow(),
            attachmentSource = attachmentSource,
            sourceAccount = sourceAccount,
            sourceAppVersion = sourceAppVersion,
            handshake = null, 
            onProgress = onProgress,
            verdictWatch = verdictWatch,
        )
    }

    








    suspend fun sendRealExport(
        target: QrPayload,
        scope: CoroutineScope,
        records: Flow<NeutralRecord>,
        attachmentSource: com.difft.android.migration.v2.container.AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        onProgress: (Float) -> Unit = {},
        
        onBodySent: () -> Unit = {},
        
        onReply: (Map<String, String>) -> Unit = {},
        
        onRejected: (String) -> Unit = {},
        
        
        sourceEnvironment: String? = null,
        sourceFormatVersion: String = FormatCompat.SUPPORTED_FORMAT_VERSION,
        
        
        estimatedTotalBytes: Long = 0L,
        
        totals: ContainerTotals? = null,
        
        
        senderAlias: String = "migration-sender",
        
        
        credentialHandoff: Boolean? = null,
        
        
        awaitPeerConfirm: Boolean = false,
        onAwaitingPeerConfirm: () -> Unit = {},
        
        
        onSessionPrepared: (SenderSessionRef) -> Unit = {},
        
        
        verdictWatch: VerdictWatch? = null,
        
        onVerdictStall: (Boolean) -> Unit = {},
        
        commitHooks: SendCommitHooks? = null,
        
        requiredCaps: Set<String> = emptySet(),
    ): Boolean = withContext(Dispatchers.IO) {
        val transferKey = target.transferKey
            ?: error("QR has no transferKey — not a migration receiver")
        L.i {
            "[MigrationV2][P2P][Tx] real export start framing=chunked (no pre-count, no disk) " +
                "handshake=${sourceEnvironment != null} estimatedTotalBytes=$estimatedTotalBytes"
        }

        sender.streamThroughPipe(
            target = target,
            transferKey = transferKey,
            declaredLength = P2pContainerPipe.UNKNOWN_LENGTH,
            estimatedTotalBytes = estimatedTotalBytes,
            records = records,
            attachmentSource = attachmentSource,
            sourceAccount = sourceAccount,
            sourceAppVersion = sourceAppVersion,
            awaitPeerConfirm = awaitPeerConfirm,
            onAwaitingPeerConfirm = onAwaitingPeerConfirm,
            handshake = sourceEnvironment?.let {
                MigrationHandshake(
                    sourceEnvironment = it,
                    sourceAccount = sourceAccount,
                    sourceAppVersion = sourceAppVersion,
                    sourceFormatVersion = sourceFormatVersion,
                    credentialHandoff = credentialHandoff,
                )
            },
            onProgress = onProgress,
            onBodySent = onBodySent,
            onReply = onReply,
            onRejected = onRejected,
            totals = totals,
            senderAlias = senderAlias,
            onSessionPrepared = onSessionPrepared,
            verdictWatch = verdictWatch,
            onVerdictStall = onVerdictStall,
            commitHooks = commitHooks,
            requiredCaps = requiredCaps,
        )
    }

    
    private fun generateTransferKey(): String =
        Base64.encodeToString(crypto.randomBytes(TRANSFER_KEY_BYTES), Base64.NO_WRAP)

    companion object {
        
        const val TRANSFER_KEY_BYTES = 32

        



        internal val RESTORE_RECEIVER_CAPS: List<String> =
            ReceiverCaps.BASE + ReceiverCaps.PENDING_ENVELOPE_V1 + ReceiverCaps.VERDICT_RETAIN_V1
    }
}
