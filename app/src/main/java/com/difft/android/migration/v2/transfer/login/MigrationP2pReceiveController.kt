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
    




    private val credentialHandoff: Boolean = false,
    private val handoffGate: MigrationHandoffGate? = null,
    private val isFreshTarget: () -> Boolean = { true },
) {

    private val appContext = context.applicationContext
    private val fusion = P2pContainerFusion(writer = writer, reader = reader, crypto = crypto)
    private val certStorage = CertStorage(File(context.filesDir, "p2p"))
    
    private val terminalStore = FileTerminalRecordStore(File(context.filesDir, "p2p"))

    private var session: P2pContainerFusion.ReceiverSession<RestoreResult>? = null

    




    private val adoptedAccount = AtomicReference("")

    




    data class ReceiverHandle(
        val qrBitmap: Bitmap?,
        val displayTarget: QrPayload,
        val pairingRegistry: PairingSessionRegistry,
    )

    







    suspend fun startReceiver(
        scope: CoroutineScope,
        onEvent: (ReceiverHttpServer.ServerEvent) -> Unit,
        onPromoted: () -> Unit,
        
        
        onCommittedUnconfirmed: () -> Unit,
        onRejected: (String) -> Unit,
        
        
        onProgress: (Float) -> Unit = {},
        
        
        onBodyComplete: () -> Unit = {},
        
        sameDevice: Boolean = false,
    ): ReceiverHandle = withContext(Dispatchers.IO) {
        stopReceiver() 
        ReceiverLinger.shared.stopNow() 

        
        
        
        
        val candidates = if (sameDevice) {
            listOf(PeerApps.LOOPBACK_IP)
        } else {
            NetworkUtils.lanCandidates().ifEmpty { throw IllegalStateException(NO_LAN_REASON) }
        }
        val ip = candidates.first()
        val cert = SelfSignedCert.loadOrCreate(certStorage, ip)

        
        
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
                    
                    
                    
                    if (e is CancellationException) throw e
                    
                    
                    
                    if (e is MigrationException.CommittedUnconfirmed) {
                        L.w { "[MigrationV2][P2P][Rx] import committed; tail unconfirmed — cold start to finish it" }
                        onCommittedUnconfirmed()
                        return@onFailure
                    }
                    onRejected(e.message ?: e.javaClass.simpleName)
                }
        }

        
        ReceiverHandle(QrPayloadCodec.encode(s.qrPayload), s.qrPayload, s.pairingRegistry)
    }

    



    fun stopReceiver() {
        session?.stop()
        session = null
    }

    




    fun lingerReceiver() {
        session?.let { ReceiverLinger.shared.keep(it) }
        session = null
    }

    




    fun lingerAfterUnsuccessfulEnd() {
        val current = session ?: return
        if (current.retireForLinger()) {
            ReceiverLinger.shared.keep(current)
            session = null
        } else {
            stopReceiver()
        }
    }

    
    fun rotatePin(): QrPayload? = session?.rotatePin()

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
