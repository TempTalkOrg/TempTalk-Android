package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.model.FileMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import java.io.File
import java.io.InputStream

/**
 * Receiver-side push-to-pull bridge between an upload body and a container consumer.
 * `UploadHandler` pushes the socket body into the [Sink] from [openWrite]; a coroutine launched
 * there pulls the same [ReceiveSpool] as an `InputStream` into [consume]. The spool is on disk, so a
 * slow import never back-pressures the socket and memory stays constant for a GB-scale container.
 *
 * The consumer's failures are not swallowed: [result] carries them, and [finalizeFile] awaits that
 * verdict so the HTTP 200 is written only once [consume] reports success. Single-use — one instance
 * handles one upload body.
 *
 * @param scope the scope the consumer is launched under; its cancellation still governs the consumer.
 * @param spoolDir the directory the body's segment files land in. Wiped by the spool on teardown.
 * @param onWriteProgress `(totalWritten, backlogBytes)` from the spool writer, on the socket thread:
 *   the free-space monitor's hook. Throwing from it stops the receive with that failure.
 * @param isSuccess whether a consumed [R] should fire the 200. A rejection returned normally must
 *   still surface as a non-200, so the sender is never told a migration completed when it did not.
 * @param consume reads the forward-only body; the bridge, not the consumer, closes the spool reader.
 * @param onProgress approximate receive progress in `[0, 1]`, counted against the sender's advisory
 *   size. Nothing is emitted when that size is unknown, leaving the UI indeterminate.
 */
class P2pContainerReceiveSink<R>(
    private val scope: CoroutineScope,
    private val spoolDir: File,
    private val onWriteProgress: (Long, Long) -> Unit = { _, _ -> },
    private val isSuccess: (R) -> Boolean = { true },
    private val onProgress: (Float) -> Unit = {},
    /**
     * Headers the 200 carries back for a successful [R]: the receiver's explicit acknowledgment of a
     * precondition the sender declared, such as the handoff's credential being installed.
     */
    private val successHeaders: (R) -> Map<String, String> = { emptyMap() },
    /**
     * The whole body is in; the consumer's verdict is now the only thing outstanding. Fires once, at
     * the top of [finalizeFile], so the UI can leave the progress bar before the import tail runs.
     */
    private val onBodyComplete: () -> Unit = {},
    private val consume: suspend (InputStream) -> R,
) : FileSink {

    private val spool = ReceiveSpool(spoolDir, onWriteProgress = onWriteProgress)

    /**
     * Completes with the consumed [R], or with the consumer's failure (HMAC mismatch, decryption
     * failure, account mismatch, cancellation). [finalizeFile] awaits it to gate the 200.
     */
    val result: CompletableDeferred<R> = CompletableDeferred()

    override fun openWrite(meta: FileMetadata): Sink {
        L.i { "[MigrationV2][P2P][Rx] body arriving fileId=${meta.id} declaredSize=${meta.size}" }
        // The sender's advisory estimate, not a real Content-Length: the progress denominator.
        // Non-positive means no estimate, so nothing is emitted and the UI stays indeterminate.
        val estimatedTotal = meta.size
        // Launch the puller the moment the body starts, on Dispatchers.Default rather than the scope's
        // own IO dispatcher: finalizeFile blocks an IO connection thread awaiting this consumer, so
        // running the consumer on the IO pool too would let pool saturation starve the await and hang
        // the transfer. The consumer's own blocking IO still runs on the IO pool inside the consumer.
        scope.launch(Dispatchers.Default) {
            runCatching {
                spool.openInput().use { input -> consume(input) }
            }.onSuccess { outcome ->
                result.complete(outcome)
            }.onFailure { e ->
                L.w { "[MigrationV2][P2P][Rx] stream consume failed: ${e.message}" }
                result.completeExceptionally(e)
            }
        }
        // Count the bytes pushed into the spool so progress reflects bytes actually arriving, throttled.
        // With no estimate there is no progress to report, leaving the UI indeterminate.
        val sink = spool.openSink()
        if (estimatedTotal <= 0L) return sink
        return ProgressCountingSink(sink, estimatedTotal, onProgress)
    }

    /**
     * Forwards every write to the spool sink while counting bytes, reporting [onProgress] against
     * [estimatedTotal] in ~[PROGRESS_DELTA] steps. Approximate: the estimate may undershoot the real
     * byte count, so the ratio is always coerced into `[0, 1]`.
     */
    private class ProgressCountingSink(
        delegate: Sink,
        private val estimatedTotal: Long,
        private val onProgress: (Float) -> Unit,
    ) : ForwardingSink(delegate) {
        private var written = 0L
        private var lastReported = -1f

        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            written += byteCount
            val ratio = (written.toFloat() / estimatedTotal).coerceIn(0f, 1f)
            if (ratio - lastReported >= PROGRESS_DELTA || ratio >= 1f && lastReported < 1f) {
                lastReported = ratio
                onProgress(ratio)
            }
        }
    }

    /**
     * Called by `UploadHandler` once the whole body is pushed. Closing the spool sink signals EOF, the
     * consumer finalizes its HMAC, and this awaits its verdict. A consume failure or a non-[isSuccess]
     * outcome returns a failed [Result], so the HTTP layer answers non-200.
     */
    // Synchronous override: the completion handshake has to block the HTTP IO thread until the
    // consumer, which runs on a different pool, reports its verdict.
    @Suppress("BanRunBlockingOutsideTests")
    override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> {
        // Before the spool closes and the verdict is awaited: a callback failure must not fail the body.
        runCatching { onBodyComplete() }
            .onFailure { e -> L.w { "[MigrationV2][P2P][Rx] body-complete callback failed: ${e.message}" } }
        // openSink() is idempotent, so the writer can be sealed without holding the reference the
        // transport was handed. EOF → the consumer finalizes the trailing-HMAC check.
        runCatching { spool.openSink().close() }
        return runCatching {
            // Blocks the HTTP-connection IO thread until the consumer reports its verdict. Deadlock-free
            // because the consumer runs on Dispatchers.Default (see openWrite), a different pool.
            val outcome = runBlocking { result.await() }
            if (!isSuccess(outcome)) {
                // A guard rejection returned as a normal result must not confirm a 200 — the sender has
                // to learn the migration did not complete.
                error("migration import did not complete successfully: $outcome")
            }
            replyHeaders = successHeaders(outcome)
            "verified outcome=$outcome"
        }
    }

    /** `FileSink.discard`: this body will never complete, so the spool is torn down with it. */
    override fun discard(meta: FileMetadata) = abort()

    /**
     * Lifecycle-level teardown with no [FileMetadata] in hand — called by the owning session's
     * `stop()`, which can fire before any upload started or between files. Deliberately distinct
     * from [discard]: that is the override the transport calls, this is the owner's.
     */
    fun abort() {
        spool.abort(null)
    }

    @Volatile
    private var replyHeaders: Map<String, String> = emptyMap()

    override fun successHeaders(meta: FileMetadata): Map<String, String> = replyHeaders

    companion object {
        /** Throttles the progress callback to ~1% steps so a GB-scale transfer does not flood it. */
        private const val PROGRESS_DELTA = 0.01f
    }
}
