package com.difft.android.migration.v2.transfer.send

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.snapshot.BackupSnapshotter
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.migration.v2.container.TransferProgress
import com.difft.android.migration.v2.export.NeutralExporterImpl
import com.difft.android.migration.v2.export.NeutralProjections
import com.difft.android.migration.v2.export.PendingEnvelopeExport
import com.difft.android.migration.v2.export.SnapshotReadSource
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.pause.SenderPauseSession
import com.difft.android.migration.v2.transfer.P2pContainerFusion
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.VerdictWatch
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.base.utils.time.ServerTimeProvider
import org.difft.app.database.models.PendingMessageModelNew
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.File
import com.difft.android.migration.v2.transfer.DeviceNames
import com.difft.android.migration.v2.transfer.MigrationEnvironment












class MigrationP2pSendController(
    context: Context,
    writer: ContainerWriter,
    reader: ContainerReader,
    crypto: BackupCrypto,
    private val snapshotter: BackupSnapshotter,
    private val environmentHelper: EnvironmentHelper,
    private val userManager: StorageBoundUserManager,
    private val appVersion: String,
    
    
    private val fusion: P2pContainerFusion = P2pContainerFusion(writer = writer, reader = reader, crypto = crypto),
    
    
    private val openReadSource: (Context, String) -> SnapshotReadSource = { ctx, path ->
        SnapshotReadSource.open(ctx, path)
    },
    
    
    private val preCount: suspend (NeutralExporterImpl) -> ContainerTotals = { it.preCount() },
    
    private val readPendingRows: (SnapshotReadSource) -> List<PendingMessageModelNew> = { it.pendingMessageNew.allObjects },
    private val clock: () -> Long = ServerTimeProvider::nowMillis,
) {

    private val appContext = context.applicationContext

    







    suspend fun sendRealExport(
        scope: CoroutineScope,
        target: QrPayload,
        onProgress: (Float) -> Unit,
        onBodySent: () -> Unit = {},
        
        
        includeAttachments: Boolean = true,
        
        
        credentialHandoff: Boolean = false,
        
        
        handoffId: String = "",
        
        
        awaitPeerConfirm: Boolean = false,
        onAwaitingPeerConfirm: () -> Unit = {},
        
        
        onReply: (Map<String, String>) -> Unit = {},
        
        onRejected: (String) -> Unit = {},
        
        onSessionPrepared: (SenderSessionRef) -> Unit = {},
        verdictWatch: VerdictWatch? = null,
        onVerdictStall: (Boolean) -> Unit = {},
        
        
        pauseSession: SenderPauseSession? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        
        
        
        val userData = userManager.getUserData()
        val account = userData?.account
        if (account.isNullOrEmpty()) {
            L.w { "[MigrationV2][P2P][Tx] send aborted — no live account (not logged in)" }
            error("not logged in")
        }
        val environment = MigrationEnvironment.wireName(environmentHelper)
        
        
        
        
        val identity = NeutralProjections.identity(userData)
        
        
        val credential = if (credentialHandoff) NeutralProjections.credential(userData, handoffId) else null
        
        
        
        if (credentialHandoff && (credential == null || identity == null)) {
            L.w { "[MigrationV2][P2P][Tx] handoff aborted — credential or identity unavailable" }
            error(HANDOFF_CREDENTIAL_UNAVAILABLE)
        }
        
        L.i { "[MigrationV2][P2P][Tx] production send start handshake=true handoff=$credentialHandoff" }

        
        
        pauseSession?.enter()
        
        val snapshot: File = snapshotter.takeSnapshot()
        var readSource: SnapshotReadSource? = null
        try {
            readSource = openReadSource(appContext, snapshot.absolutePath)
            val exporter = NeutralExporterImpl(readSource).apply {
                selfUid = account
                this.includeAttachments = includeAttachments
            }
            
            
            
            val counted = preCount(exporter)
            
            
            
            val pendingEnvelopes = if (pauseSession != null) {
                PendingEnvelopeExport.select(readPendingRows(readSource), clock())
            } else {
                emptyList()
            }
            val requiredCaps = if (pendingEnvelopes.isEmpty()) emptySet() else setOf(ReceiverCaps.PENDING_ENVELOPE_V1)
            val extraRecords = (if (identity == null) 0 else 1) + (if (credential == null) 0 else 1) + pendingEnvelopes.size
            val totals = counted.copy(recordCount = counted.recordCount + extraRecords)
            L.i {
                "[MigrationV2][P2P][Tx] totals records=${totals.recordCount} " +
                    "attachments=${totals.attachmentCount} attachmentBytes=${totals.attachmentBytes} " +
                    "includeAttachments=$includeAttachments"
            }
            
            
            val estimatedTotal = totals.recordCount * TransferProgress.RECORD_WEIGHT_BYTES + totals.attachmentBytes
            
            
            
            val records = exporter.export().let { dbRecords ->
                if (identity == null && credential == null && pendingEnvelopes.isEmpty()) {
                    dbRecords
                } else {
                    dbRecords.onStart {
                        if (identity != null) emit(NeutralRecord.Identity(identity))
                        if (credential != null) emit(NeutralRecord.Credential(credential))
                        pendingEnvelopes.forEach { emit(it) }
                    }
                }
            }
            fusion.sendRealExport(
                target = target,
                scope = scope,
                records = records,
                attachmentSource = exporter.attachmentByteSource(),
                sourceAccount = account, 
                sourceAppVersion = appVersion, 
                sourceEnvironment = environment, 
                sourceFormatVersion = FormatCompat.SUPPORTED_FORMAT_VERSION,
                onProgress = onProgress,
                onBodySent = onBodySent,
                onReply = onReply,
                onRejected = onRejected,
                estimatedTotalBytes = estimatedTotal,
                totals = totals,
                senderAlias = DeviceNames.local(appContext),
                credentialHandoff = if (credentialHandoff) true else null,
                awaitPeerConfirm = awaitPeerConfirm,
                onAwaitingPeerConfirm = onAwaitingPeerConfirm,
                onSessionPrepared = onSessionPrepared,
                verdictWatch = verdictWatch,
                onVerdictStall = onVerdictStall,
                commitHooks = pauseSession,
                requiredCaps = requiredCaps,
            )
        } finally {
            
            
            
            runCatching { readSource?.close() }
                .onFailure { L.w { "[MigrationV2][P2P][Tx] snapshot close failed: ${it.message}" } }
            if (snapshot.exists()) {
                runCatching { snapshot.delete() }
                    .onFailure { L.w { "[MigrationV2][P2P][Tx] snapshot delete failed: ${it.message}" } }
            }
        }
    }

    companion object {
        



        const val HANDOFF_CREDENTIAL_UNAVAILABLE = "handoff-credential-unavailable"
    }
}
