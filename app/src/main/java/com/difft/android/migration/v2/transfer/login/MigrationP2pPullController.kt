package com.difft.android.migration.v2.transfer.login

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.migration.v2.restore.RestoreOrchestrator
import com.difft.android.migration.v2.restore.RestoreResult
import com.difft.android.migration.v2.transfer.DeviceNames
import com.difft.android.migration.v2.transfer.MigrationEnvironment
import com.difft.android.migration.v2.transfer.MigrationHandshakeGate
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.PrepareUploadRejectedException
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.upload.MigrationGateDecision
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import com.difft.android.p2p.engine.upload.TransferMeter
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okio.Pipe
import okio.buffer

/**
 * This device pulling its history from a Desktop that displayed an export QR (`dir = "download"`).
 * The Desktop stays the HTTPS server and this device is the client for every request:
 * `prepare-download`, then the Desktop's handshake through the same [MigrationHandshakeGate] the
 * push direction applies before any byte, then `download` streamed straight into
 * [RestoreOrchestrator.restoreFromStream], then `download-result` — the only way the Desktop can
 * learn the verdict in this direction. The transfer key rides in the QR and is never logged.
 */
class MigrationP2pPullController(
    context: Context,
    private val orchestrator: RestoreOrchestrator,
    private val environmentHelper: EnvironmentHelper,
    private val appVersion: String,
    private val loggingInAccount: String,
) {
    private val appContext = context.applicationContext

    /**
     * Runs the whole pull and returns the import outcome. Throws [PrepareUploadRejectedException] with
     * a wire reason token when the Desktop or the local gate refuses the pairing; other failures
     * propagate as thrown.
     */
    /**
     * [onProgress] reports the ratio in `[0,1]` and the byte total it is measured against.
     * [onBodyComplete] fires once the whole body has been pumped off the socket, while the import tail
     * and the promote still run, so the screen can leave the progress bar instead of freezing at 100%.
     */
    suspend fun pull(
        target: QrPayload,
        onProgress: (Float, Long) -> Unit,
        onBodyComplete: () -> Unit = {},
    ): RestoreResult {
        val transferKey = target.transferKey
            ?: throw PrepareUploadRejectedException(PrepareUploadReasons.INVALID_BODY)
        val client = SenderHttpClient(target)
        val self = DeviceInfo(alias = DeviceNames.local(appContext), fingerprint = "rx-${UUID.randomUUID().toString().take(8)}")
        val handshake = MigrationHandshake(
            sourceEnvironment = MigrationEnvironment.wireName(environmentHelper),
            sourceAccount = loggingInAccount,
            sourceAppVersion = appVersion,
            sourceFormatVersion = FormatCompat.SUPPORTED_FORMAT_VERSION,
        )
        L.i { "[MigrationV2][P2P][Pull] prepare-download → ${target.alias} @ ${target.ip}:${target.port}" }
        val prepared = client.prepareDownload(self, target.pin, handshake)
        val (fileId, meta) = prepared.files.entries.firstOrNull()?.toPair()
            ?: throw PrepareUploadRejectedException(PrepareUploadReasons.INVALID_BODY)
        val token = prepared.tokens[fileId]
            ?: throw PrepareUploadRejectedException(PrepareUploadReasons.INVALID_BODY)

        // The Desktop's identity gates here exactly as an uploader's does at prepare-upload; a missing
        // section is a contract violation, not a pass.
        val peerHandshake = prepared.migration
            ?: throw PrepareUploadRejectedException(PrepareUploadReasons.INVALID_BODY)
        val gate = MigrationHandshakeGate(environmentHelper = environmentHelper, loggingInAccount = loggingInAccount)
        when (val decision = gate.validate(peerHandshake)) {
            is MigrationGateDecision.Reject -> {
                client.postDownloadResult(prepared.sessionId, fileId, token, ok = false, error = decision.reason.wire)
                throw PrepareUploadRejectedException(decision.reason.wire)
            }
            MigrationGateDecision.Accept -> Unit
        }

        L.i { "[MigrationV2][P2P][Pull] download start declaredSize=${meta.size}" }
        val result = try {
            client.download(prepared.sessionId, fileId, token).useHangingUpOnCancel { response ->
                val total = response.body.contentLength().takeIf { it > 0L } ?: meta.size
                pumpThroughPipe(response, onBodyComplete) { input ->
                    orchestrator.restoreFromStream(
                        source = CountingInputStream(input, total, onProgress),
                        transferKey = transferKey,
                        currentVerifyAccount = loggingInAccount,
                    )
                }
            }
        } catch (e: CancellationException) {
            notifyCancelled(client, prepared.sessionId)
            throw e
        } catch (e: Throwable) {
            client.postDownloadResult(prepared.sessionId, fileId, token, ok = false, error = null)
            throw e
        }
        // The outcome is already decided locally (a promoted import is on disk). Failing to tell the
        // Desktop must not turn it into a failure here; the Desktop times out on its own.
        try {
            client.postDownloadResult(
                prepared.sessionId,
                fileId,
                token,
                ok = result is RestoreResult.ReadyForSignIn,
                error = downloadResultError(result),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[MigrationV2][P2P][Pull] download-result not delivered (${e.javaClass.simpleName}: ${e.message}); local outcome stands" }
        }
        return result
    }

    /**
     * The hang-up already tells the Desktop the phone is gone; naming the session as well lets it
     * return to its code page instead of reporting an aborted download. Best-effort and bounded: the
     * coroutine is already cancelled, so this runs non-cancellable with its own short deadline.
     */
    private suspend fun notifyCancelled(client: SenderHttpClient, sessionId: String) {
        withContext(NonCancellable) {
            withTimeoutOrNull(CANCEL_NOTICE_TIMEOUT_MS) { client.cancel(sessionId) }
        }
    }

    /**
     * Decouple the socket read from the import, as the push path does: one coroutine pumps the body
     * into a bounded [Pipe] through a [TransferMeter], the consumer reads the other end. The socket is
     * drained while the importer writes, and the meter's log tells a slow link from a slow sender. A
     * pump failure closes the sink so the consumer sees a truncated stream, the same outcome as before.
     */
    private suspend fun <T> pumpThroughPipe(
        response: Response,
        onBodyComplete: () -> Unit,
        consume: suspend (InputStream) -> T,
    ): T = coroutineScope {
        val pipe = Pipe(PIPE_BUFFER_BYTES)
        val pump = launch(Dispatchers.IO) {
            try {
                pipe.sink.buffer().use { sink ->
                    TransferMeter("pull").pump(response.body.source(), sink)
                    // The last byte is off the socket; only the import's verdict is outstanding. A
                    // callback failure must not turn a complete body into a truncated one.
                    runCatching { onBodyComplete() }
                        .onFailure { e -> L.w { "[MigrationV2][P2P][Pull] body-complete callback failed: ${e.message}" } }
                }
            } catch (e: IOException) {
                L.w { "[MigrationV2][P2P][Pull] body pump ended early: ${e.javaClass.simpleName}: ${e.message}" }
                runCatching { pipe.sink.close() }
            }
        }
        try {
            pipe.source.buffer().inputStream().use { input -> consume(input) }
        } finally {
            // Consumer done or bailed: closing both ends fails a pump parked on a full pipe or on a
            // socket read, so the scope does not wait for the rest of the body.
            runCatching { pipe.source.close() }
            runCatching { response.body.close() }
            pump.cancel()
        }
    }

    private companion object {
        const val CANCEL_NOTICE_TIMEOUT_MS = 3_000L

        /** 1 MiB high-water mark, matching the push path's receive pipe. */
        const val PIPE_BUFFER_BYTES = 1L * 1024 * 1024
    }

    /**
     * Reports bytes consumed against [total] in ~1% steps, like the push path's sink; silent when the
     * size is unknown. Every report costs a notification post, so per-read reporting throttles a slow
     * phone's whole pull.
     */
    private class CountingInputStream(
        source: InputStream,
        private val total: Long,
        private val onProgress: (Float, Long) -> Unit,
    ) : FilterInputStream(source) {
        private var seen = 0L
        private var lastReported = -1f

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(n: Long) {
            seen += n
            if (total <= 0L) return
            val ratio = (seen.toFloat() / total).coerceIn(0f, 1f)
            if (ratio - lastReported >= PROGRESS_DELTA || (ratio >= 1f && lastReported < 1f)) {
                lastReported = ratio
                onProgress(ratio, total)
            }
        }

        private companion object {
            const val PROGRESS_DELTA = 0.01f
        }
    }
}

/**
 * The `download-result` error token for an import outcome, from the wire set shared with Desktop.
 * Null on success and for failures the set has no name for; the Desktop then shows its generic
 * failure, which is still truthful.
 */
internal fun downloadResultError(result: RestoreResult): String? = when (result) {
    is RestoreResult.ReadyForSignIn -> null
    is RestoreResult.Rejected -> downloadResultError(result.error)
    is RestoreResult.Failed -> downloadResultError(result.error)
}

private fun downloadResultError(error: MigrationError): String? = when (error) {
    MigrationError.IdentityInvalid -> DOWNLOAD_RESULT_IDENTITY_MISMATCH
    MigrationError.AccountMismatch -> MigrationRejectReason.ACCOUNT_MISMATCH.wire
    MigrationError.UnsupportedVersion, MigrationError.VersionTooNew -> MigrationRejectReason.VERSION_INCOMPATIBLE.wire
    else -> null
}

/** Desktop's token for a carried identity that cannot be used; distinct from our internal error code. */
internal const val DOWNLOAD_RESULT_IDENTITY_MISMATCH = "identity-mismatch"

/**
 * [Response.use] whose body is closed the moment the calling coroutine is cancelled, not when the
 * reader next comes up for air. A blocking read on the body notices cancellation only between frames
 * — seconds on a large attachment — and the peer learns the phone left only when the socket closes.
 * Cancellation still surfaces as cancellation, never as the IOException the closed body throws into
 * the reader.
 */
internal suspend fun <T> Response.useHangingUpOnCancel(block: suspend (Response) -> T): T = use { response ->
    coroutineScope {
        val hangUp = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { response.close() }
            }
        }
        try {
            block(response)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            hangUp.cancel()
        }
    }
}
