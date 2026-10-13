package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.io.Sha256
import com.difft.android.p2p.engine.io.TransferCommittedUnconfirmed
import com.difft.android.p2p.engine.io.TransferStorageFault
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.session.SessionManager
import okio.Source
import okio.buffer

/**
 * Result of a single completed upload (engine-level, transport-agnostic).
 *
 * @property landedPath sink-defined location of the finalized file.
 * @property bytes total bytes streamed.
 * @property sha256 the actual SHA-256 (hex) computed over the streamed bytes.
 */
data class UploadResult(
    val fileId: String,
    val landedPath: String,
    val bytes: Long,
    val sha256: String,
    /** The sink's [FileSink.successHeaders]: what the 200 must carry back to the sender. */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Engine handler for `POST /api/localsend/v2/upload?sessionId=&fileId=&token=`.
 *
 * Streams the request-body [Source] straight into the [FileSink] without buffering it whole,
 * computes the SHA-256 while streaming, verifies it against the declared metadata digest, then
 * marks the slot done. Authorization (sessionId + token + IP) is delegated to
 * [SessionManager.authorizeUpload], which answers 403 on a mismatch.
 *
 * Throws [ProtocolError] on auth failure or integrity mismatch; the adapter maps the status.
 */
class UploadHandler(
    private val sessionManager: SessionManager,
    private val fileSink: FileSink,
) {
    fun handle(
        sessionId: String,
        fileId: String,
        token: String,
        sourceIp: String,
        body: Source,
        /** Fires once the slot is authorized, before the first body byte is read. */
        onAuthorized: () -> Unit = {},
        /**
         * Fires once the whole body has been streamed into the sink, before the verdict work (digest
         * check + [FileSink.finalizeFile]) begins. The transport uses it to start signalling liveness
         * to the sender: from here on nothing arrives on the wire until the verdict is written.
         */
        onBodyComplete: () -> Unit = {},
    ): UploadResult {
        val slot = sessionManager.authorizeUpload(sessionId, fileId, token, sourceIp)
        onAuthorized()
        val meta = slot.meta

        val hashingSink = Sha256.hashingSink(fileSink.openWrite(meta))
        // Metered instead of writeAll: the shape of the byte arrival (stall share, per-window rate,
        // read size) is what tells a slow link apart from a sender that cannot keep up — see
        // [TransferMeter]. Same streaming guarantee, nothing buffered whole.
        val bytes = try {
            hashingSink.buffer().use { buffered ->
                body.use { src -> TransferMeter(fileId).pump(src, buffered) }
            }
        } catch (e: Exception) {
            // The body never finished: nothing to verify, nothing worth keeping on disk.
            fileSink.discard(meta)
            // Out of space mid-body must reach the sender as a typed 403 on the still-open connection:
            // only a ProtocolError is written to the socket by the server's catch chain, so a bare
            // rethrow here would surface as a connection reset with no reason at all. No 102 can be in
            // flight yet — the heartbeat starts at onBodyComplete — so the 403 cannot interleave.
            if (e is TransferStorageFault) {
                throw ProtocolError.Forbidden(MigrationRejectReason.INSUFFICIENT_STORAGE.wire)
            }
            throw e
        }
        onBodyComplete()
        val actualSha = hashingSink.hash.hex()

        val expected = meta.sha256
        if (expected != null && !actualSha.equals(expected, ignoreCase = true)) {
            // Integrity failure: the file is not finalized and the slot is not marked done.
            fileSink.finalizeFile(meta, expected) // gives the sink a chance to clean up partials
            throw ProtocolError.Internal("sha256 mismatch fileId=$fileId exp=$expected act=$actualSha")
        }

        val landed = fileSink.finalizeFile(meta, expected).getOrElse { e ->
            when (e) {
                // Nothing was committed, so the honest answer is a typed 403 the sender can retry on.
                is TransferStorageFault ->
                    throw ProtocolError.Forbidden(MigrationRejectReason.INSUFFICIENT_STORAGE.wire)
                // Committed but unprovable: the transport answers nothing at all, so the sender reads
                // "unknown" rather than a verdict this receiver cannot stand behind.
                is TransferCommittedUnconfirmed -> throw e
                else -> throw ProtocolError.Internal("finalize failed fileId=$fileId: ${e.message}")
            }
        }
        sessionManager.markDone(sessionId, fileId)
        return UploadResult(fileId = fileId, landedPath = landed, bytes = bytes, sha256 = actualSha, headers = fileSink.successHeaders(meta))
    }
}
