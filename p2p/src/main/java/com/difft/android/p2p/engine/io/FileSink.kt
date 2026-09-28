package com.difft.android.p2p.engine.io

import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.upload.UploadReplyHeaders
import okio.Sink

/**
 * A writable destination the receiver streams incoming bytes into. Transport-agnostic: an
 * on-disk download dir today ([com.difft.android.p2p.android.file.DiskFileSink]), a future
 * file-fallback container could plug a different impl. Okio `Sink` keeps the engine pure.
 */
interface FileSink {

    /** Opens a stream for [meta]. Caller writes the body then calls [finalizeFile]. */
    fun openWrite(meta: FileMetadata): Sink

    /**
     * Finalizes the file after the body has been fully written, verifying its integrity.
     *
     * @param expectedSha256 the declared hex digest; null requests no integrity check.
     * @return the landed path, or failure when the hash mismatched.
     */
    fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String>

    /**
     * The body for [meta] will never complete (peer dropped, socket died): drop whatever was written
     * for it. Default no-op for sinks that hold nothing on their own.
     */
    fun discard(meta: FileMetadata) {}

    /**
     * Headers the transport adds to the upload's 200 once [finalizeFile] succeeded — the receiver's
     * explicit acknowledgment of a precondition the sender declared ([UploadReplyHeaders]). Empty
     * by default: a plain file receive acknowledges nothing beyond the status line.
     */
    fun successHeaders(meta: FileMetadata): Map<String, String> = emptyMap()
}
