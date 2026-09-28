package com.difft.android.migration.v2.transfer

import com.difft.android.migration.v2.container.ArchiveSink
import com.difft.android.p2p.engine.io.FileSource
import okio.Pipe
import okio.Source
import okio.buffer
import java.io.OutputStream

/**
 * Sender-side push-to-pull bridge that streams a container straight into the upload body without
 * ever writing a `.ttmig` file. One [okio.Pipe] joins the migration [ArchiveSink] the writer fills to
 * the `:p2p` [FileSource] the uploader drains; the pipe's bounded buffer supplies the backpressure
 * that keeps memory constant.
 *
 * The writer and the uploader must run on separate coroutines: a full pipe blocks the writer until
 * the reader drains, so driving both from one thread deadlocks. [P2pContainerFusion] wires that up.
 *
 * @param declaredLength exact byte length for a `Content-Length` upload, or [UNKNOWN_LENGTH] to
 *   stream with chunked transfer-encoding, which a real export needs because its length is not known
 *   up front.
 * @param bufferBytes the pipe's high-water mark; backpressure kicks in above it.
 */
class P2pContainerPipe(
    private val declaredLength: Long,
    private val fileName: String = "migration.ttmig",
    bufferBytes: Long = DEFAULT_PIPE_BUFFER,
) {
    private val pipe = Pipe(bufferBytes)

    /**
     * The write end. `ContainerWriter` opens it once and closes it when the container is written,
     * which signals end-of-stream to the reader. `abort` also closes it, so a failed or cancelled
     * write fails a waiting uploader instead of hanging it.
     */
    val archiveSink: ArchiveSink = object : ArchiveSink {
        override fun openStream(): OutputStream = pipe.sink.buffer().outputStream()
        override fun abort() {
            runCatching { pipe.sink.close() }
        }
    }

    /**
     * The upload read end, streamed as the request body. A non-negative `size` becomes a
     * `Content-Length`; [UNKNOWN_LENGTH] drives chunked transfer-encoding. The mime type is
     * octet-stream because the container header is self-describing and the receiver never branches
     * on it.
     */
    val fileSource: FileSource = object : FileSource {
        override val fileName: String = this@P2pContainerPipe.fileName
        override val size: Long = declaredLength
        override val mimeType: String = "application/octet-stream"
        override val sha256: String? = null // streamed; the container's own trailing HMAC is the gate
        override fun openRead(): Source = pipe.source
    }

    /**
     * Closes the read end, so a writer parked on a full buffer wakes with an IOException instead of
     * waiting for a reader that will never come. Cancelling the uploader's coroutine cannot interrupt
     * a blocked pipe write, so this is the only way out. Idempotent.
     */
    fun abortRead() {
        runCatching { pipe.source.close() }
    }

    companion object {
        /** 1 MiB high-water mark: enough to keep the socket fed without buffering the archive. */
        const val DEFAULT_PIPE_BUFFER: Long = 1L * 1024 * 1024

        /** [declaredLength] sentinel: stream with chunked transfer-encoding (unknown length). */
        const val UNKNOWN_LENGTH: Long = -1L
    }
}
