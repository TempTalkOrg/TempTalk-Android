package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.v2.container.AttachmentByteSource
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.container.ProgressReporter
import com.difft.android.migration.v2.container.counting
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.model.QrPayload
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull





class ReceiverCapabilityMissingException(val missing: Set<String>) : IllegalStateException(RECEIVER_UPDATE_REQUIRED) {
    companion object {
        const val RECEIVER_UPDATE_REQUIRED = "migration-receiver-update-required"
    }
}









internal class P2pSenderPipeline(private val writer: ContainerWriter) {

    







    suspend fun streamThroughPipe(
        target: QrPayload,
        transferKey: String,
        declaredLength: Long,
        
        
        estimatedTotalBytes: Long,
        records: Flow<NeutralRecord>,
        attachmentSource: AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        handshake: MigrationHandshake?,
        awaitPeerConfirm: Boolean = false,
        onAwaitingPeerConfirm: () -> Unit = {},
        onProgress: (Float) -> Unit,
        onBodySent: () -> Unit = {},
        onReply: (Map<String, String>) -> Unit = {},
        
        onRejected: (String) -> Unit = {},
        senderAlias: String = "migration-sender",
        totals: ContainerTotals? = null,
        onSessionPrepared: (SenderSessionRef) -> Unit = {},
        verdictWatch: VerdictWatch? = null,
        onVerdictStall: (Boolean) -> Unit = {},
        commitHooks: SendCommitHooks? = null,
        
        requiredCaps: Set<String> = emptySet(),
    ): Boolean {
        val pipe = P2pContainerPipe(declaredLength = declaredLength)
        val client = SenderHttpClient(target)
        val fileId = UUID.randomUUID().toString()
        
        
        
        
        val advertisedSize =
            if (declaredLength >= 0L) declaredLength else estimatedTotalBytes.coerceAtLeast(0L)
        val meta = FileMetadata(
            id = fileId,
            fileName = pipe.fileSource.fileName,
            size = advertisedSize,
            fileType = pipe.fileSource.mimeType,
            sha256 = null, 
        )
        val self = DeviceInfo(alias = senderAlias, fingerprint = "tx-${fileId.take(8)}")
        
        
        val prepared = if (awaitPeerConfirm) {
            PeerConfirmRetry.run(onWaiting = onAwaitingPeerConfirm) {
                client.prepareUpload(self, mapOf(fileId to meta), target.pin, handshake)
            }
        } else {
            client.prepareUpload(self, mapOf(fileId to meta), target.pin, handshake)
        }
        val token = prepared.files[fileId] ?: run {
            L.w { "[MigrationV2][P2P][Tx] no upload token for fileId" }
            return false
        }
        val session = SenderSessionRef(
            ip = target.ip,
            port = target.port,
            fingerprint = target.fp,
            sessionId = prepared.sessionId,
            sessionToken = prepared.sessionToken,
            caps = prepared.caps.orEmpty(),
        )
        L.i { "[MigrationV2][P2P][Tx] session prepared caps=${session.caps} statusToken=${session.sessionToken != null}" }
        onSessionPrepared(session)

        
        
        
        
        val reporter = ProgressReporter(onProgress).also { it.start(totals) }
        val countedRecords = if (totals != null) records.onEach { reporter.recordDone() } else records
        val countedAttachments = if (totals != null) attachmentSource.counting(reporter::attachmentBytes) else attachmentSource

        
        
        return abortingSessionOnFailure(client, prepared.sessionId, prepared.sessionToken, commitHooks) {
            requireCaps(requiredCaps, session)
            coroutineScope {
                val writeJob = async(Dispatchers.IO) {
                    writer.write(
                        records = countedRecords,
                        sink = pipe.archiveSink,
                        passphrase = transferKey, 
                        attachmentBytes = countedAttachments,
                        sourceAccount = sourceAccount,
                        sourceAppVersion = sourceAppVersion,
                        totals = totals,
                        beforeFinalize = commitHooks?.let(::finalizeHook),
                    )
                }
                
                val bodySent = CompletableDeferred<Long>()
                val uploadJob = async(Dispatchers.IO) {
                    runCatching {
                        client.upload(
                            sessionId = prepared.sessionId,
                            fileId = fileId,
                            token = token,
                            source = pipe.fileSource,
                            onProgress = { sent, total ->
                                if (totals != null) return@upload 
                                
                                
                                val denominator = if (total > 0L) total else estimatedTotalBytes
                                val ratio = if (denominator > 0L) (sent.toFloat() / denominator).coerceIn(0f, 1f) else 0f
                                onProgress(ratio)
                            },
                            onBodySent = {
                                bodySent.complete(System.currentTimeMillis())
                                onBodySent()
                            },
                            onReply = onReply,
                            onRejected = onRejected,
                        )
                    }.onFailure {
                        
                        
                        
                        pipe.abortRead()
                    }
                }
                
                
                
                val watchJob = if (verdictWatch != null && session.supportsStatus) {
                    async(Dispatchers.IO) {
                        val sentAt = bodySent.await()
                        verdictWatch.run(
                            bodySentAt = sentAt,
                            status = { client.status(session.sessionId, session.sessionToken.orEmpty()) },
                            onStall = onVerdictStall,
                        )
                    }
                } else {
                    null
                }
                val ok = awaitVerdict(uploadJob, watchJob, bodySent)
                writeJob.await()
                L.i { "[MigrationV2][P2P][Tx] upload complete result=$ok" }
                ok
            }
        }
    }

    




    private fun requireCaps(required: Set<String>, session: SenderSessionRef) {
        val missing = required - session.caps.toSet()
        if (missing.isNotEmpty()) {
            L.w { "[MigrationV2][P2P][Tx] event=receiver_caps_missing caps=$missing" }
            throw ReceiverCapabilityMissingException(missing)
        }
    }

    






    private suspend fun awaitVerdict(
        uploadJob: Deferred<Result<Boolean>>,
        watchJob: Deferred<Verdict>?,
        bodySent: CompletableDeferred<Long>,
    ): Boolean {
        if (watchJob == null) return uploadJob.await().getOrThrow()
        val first: Any = select {
            uploadJob.onAwait { it }
            watchJob.onAwait { it }
        }
        if (first is Verdict) {
            L.i { "[MigrationV2][P2P][Tx] verdict from /status: $first" }
            uploadJob.cancel()
            return first.confirmed()
        }
        @Suppress("UNCHECKED_CAST")
        val result = first as Result<Boolean>
        return result.fold(
            onSuccess = { ok ->
                watchJob.cancel()
                ok
            },
            onFailure = { e ->
                if (e is kotlin.coroutines.cancellation.CancellationException || !bodySent.isCompleted) {
                    
                    watchJob.cancel()
                    throw e
                }
                L.w { "[MigrationV2][P2P][Tx] upload connection ended before the verdict, asking /status: ${e.message}" }
                watchJob.await().confirmed()
            },
        )
    }

    




    private fun finalizeHook(hooks: SendCommitHooks): suspend () -> Unit = {
        hooks.beforeFinalize()
        hooks.onEndMarker()
    }

    private fun Verdict.confirmed(): Boolean =
        if (this is Verdict.Succeeded) true else throw SendVerdictException(this)

    






    private suspend inline fun <T> abortingSessionOnFailure(
        client: SenderHttpClient,
        sessionId: String,
        sessionToken: String?,
        hooks: SendCommitHooks?,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Throwable) {
            
            
            
            if (e !is SendVerdictException) {
                val answer = withContext(NonCancellable) {
                    withTimeoutOrNull(CANCEL_NOTICE_TIMEOUT_MS) { client.cancel(sessionId, sessionToken) }
                }
                hooks?.onAbortAnswer(answer)
            }
            throw e
        }

    
    suspend fun countContainerLength(
        records: List<NeutralRecord>,
        attachmentSource: AttachmentByteSource,
        transferKey: String,
        sourceAccount: String,
        sourceAppVersion: String,
    ): Long {
        val counter = ContainerSizeCounter()
        writer.write(
            records = records.asFlow(),
            sink = counter,
            passphrase = transferKey,
            attachmentBytes = attachmentSource,
            sourceAccount = sourceAccount,
            sourceAppVersion = sourceAppVersion,
        )
        return counter.byteCount
    }

    private companion object {
        
        const val CANCEL_NOTICE_TIMEOUT_MS = 3_000L
    }
}
