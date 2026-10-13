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

/**
 * The sender half of [P2pContainerFusion]: the pipe between the container writer and the upload,
 * the verdict race that decides what the send returns, and the `/cancel` notice a stopped sender
 * owes its receiver. Split out so the fusion class stays the module edge rather than also being the
 * upload state machine; nothing here touches the receive side.
 *
 * The transfer key is a live encryption key and is never logged here.
 */
internal class P2pSenderPipeline(private val writer: ContainerWriter) {

    /**
     * Shared sender mechanics for both send paths: open a [P2pContainerPipe], `prepare-upload`, then
     * run the writer (filling the pipe) and the uploader (draining it) on separate coroutines, since a
     * full pipe blocks the writer until the reader drains and one thread would deadlock. Both are
     * children of this call, so whichever fails first cancels the other and is what the caller sees.
     * [declaredLength] selects the framing: non-negative gives a `Content-Length`,
     * [P2pContainerPipe.UNKNOWN_LENGTH] gives chunked transfer-encoding.
     */
    suspend fun streamThroughPipe(
        target: QrPayload,
        transferKey: String,
        declaredLength: Long,
        // Approximate progress denominator for the chunked path; 0 on the Content-Length path, where
        // `declaredLength` is both the real size and the denominator.
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
        senderAlias: String = "migration-sender",
        totals: ContainerTotals? = null,
        onSessionPrepared: (SenderSessionRef) -> Unit = {},
        verdictWatch: VerdictWatch? = null,
        onVerdictStall: (Boolean) -> Unit = {},
    ): Boolean {
        val pipe = P2pContainerPipe(declaredLength = declaredLength)
        val client = SenderHttpClient(target)
        val fileId = UUID.randomUUID().toString()
        // Advertised size in the prepare-upload metadata, which the session slot does not gate on: the
        // real container size on the Content-Length path, the estimate on the chunked path so the
        // receiver can drive its own bar. Advisory only — the receiver still de-chunks by
        // Transfer-Encoding.
        val advertisedSize =
            if (declaredLength >= 0L) declaredLength else estimatedTotalBytes.coerceAtLeast(0L)
        val meta = FileMetadata(
            id = fileId,
            fileName = pipe.fileSource.fileName,
            size = advertisedSize,
            fileType = pipe.fileSource.mimeType,
            sha256 = null, // container's own trailing HMAC is the integrity gate (not a pre-hash)
        )
        val self = DeviceInfo(alias = senderAlias, fingerprint = "tx-${fileId.take(8)}")
        // The handshake, when present, carries the source identity, so the receiver's gate can reject
        // with a typed 403 before any bytes flow.
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

        // Progress. With totals, count what the writer frames — records and attachment payload — against
        // the same totals the receiver reads from the manifest, so both bars run the one formula and end
        // together at 100%. The writer runs at most one pipe buffer (1 MiB) ahead of the wire, so this
        // leads the true upload by that much and no more. Without totals, the byte estimate below stands.
        val reporter = ProgressReporter(onProgress).also { it.start(totals) }
        val countedRecords = if (totals != null) records.onEach { reporter.recordDone() } else records
        val countedAttachments = if (totals != null) attachmentSource.counting(reporter::attachmentBytes) else attachmentSource

        // Writer and uploader on separate coroutines, both children of this scope so a failure in
        // either is contained. Whatever ends this early, the receiver is told by name.
        return abortingSessionOnFailure(client, prepared.sessionId, prepared.sessionToken) {
            coroutineScope {
                val writeJob = async(Dispatchers.IO) {
                    writer.write(
                        records = countedRecords,
                        sink = pipe.archiveSink,
                        passphrase = transferKey, // R3: ephemeral key in the passphrase position; never logged
                        attachmentBytes = countedAttachments,
                        sourceAccount = sourceAccount,
                        sourceAppVersion = sourceAppVersion,
                        totals = totals,
                    )
                }
                // The moment the last body byte left, for the verdict watch's clocks.
                val bodySent = CompletableDeferred<Long>()
                val uploadJob = async(Dispatchers.IO) {
                    runCatching {
                        client.upload(
                            sessionId = prepared.sessionId,
                            fileId = fileId,
                            token = token,
                            source = pipe.fileSource,
                            onProgress = { sent, total ->
                                if (totals != null) return@upload // the reporter above owns progress
                                // Prefer the real Content-Length, else the estimate on the chunked path; neither known
                                // leaves the bar indeterminate. The estimate is approximate, so coerce into [0,1].
                                val denominator = if (total > 0L) total else estimatedTotalBytes
                                val ratio = if (denominator > 0L) (sent.toFloat() / denominator).coerceIn(0f, 1f) else 0f
                                onProgress(ratio)
                            },
                            onBodySent = {
                                bodySent.complete(System.currentTimeMillis())
                                onBodySent()
                            },
                            onReply = onReply,
                        )
                    }.onFailure {
                        // The uploader is gone before it drained the pipe — it never opened the body, or
                        // was cancelled first. The writer may be parked on a full buffer, and cancelling
                        // its coroutine cannot wake a blocked write; closing the read end does.
                        pipe.abortRead()
                    }
                }
                // The receiver's own account of the session, on a second connection, from the moment
                // the body is in. Only with a receiver that serves it; a Desktop that does not keeps
                // the upload response as the one word, exactly as before.
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

    /**
     * Whichever speaks first decides — with two exceptions. An upload connection that ends without a
     * response after the body was sent (a timeout, a reset, a receiver too busy to answer) is not a
     * verdict: the watch on its own connection still is, so the wait goes on there. And a verdict
     * that is not `succeeded` is thrown as [SendVerdictException], so the caller can tell a receiver
     * that said `failed` from one that said nothing at all, and so no `/cancel` follows it.
     */
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
                    // Cancelled, or the body never fully left: there is nothing to ask the receiver about.
                    watchJob.cancel()
                    throw e
                }
                L.w { "[MigrationV2][P2P][Tx] upload connection ended before the verdict, asking /status: ${e.message}" }
                watchJob.await().confirmed()
            },
        )
    }

    private fun Verdict.confirmed(): Boolean =
        if (this is Verdict.Succeeded) true else throw SendVerdictException(this)

    /**
     * iOS parity: a sender that stops — cancelled, or its upload failed — names its session on
     * `POST /cancel`, so the receiver reads the abort as the source leaving rather than a dropped
     * link. The hang-up itself has already happened by then (cancelling the call closes the socket),
     * so the notice runs non-cancellable with its own short deadline, best-effort.
     */
    private suspend inline fun <T> abortingSessionOnFailure(
        client: SenderHttpClient,
        sessionId: String,
        sessionToken: String?,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Throwable) {
            // A verdict the receiver itself gave, or a wait that ran out: the receiver is not to be
            // told to stop. On a timeout in particular a cancel could only turn "still importing" into
            // an ambiguity the status route exists to remove.
            if (e !is SendVerdictException) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(CANCEL_NOTICE_TIMEOUT_MS) { client.cancel(sessionId, sessionToken) }
                }
            }
            throw e
        }

    /** Dry-encodes the container through [ContainerSizeCounter] to learn its exact byte length. */
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
        /** How long the sender waits for its `/cancel` notice to land before giving up on it. */
        const val CANCEL_NOTICE_TIMEOUT_MS = 3_000L
    }
}
