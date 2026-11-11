package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.io.Sha256
import com.difft.android.p2p.engine.io.TransferCommittedUnconfirmed
import com.difft.android.p2p.engine.io.TransferStorageFault
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.session.SessionManager
import okio.Source
import okio.buffer








data class UploadResult(
    val fileId: String,
    val landedPath: String,
    val bytes: Long,
    val sha256: String,
    
    val headers: Map<String, String> = emptyMap(),
)











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
        
        onAuthorized: () -> Unit = {},
        




        onBodyComplete: () -> Unit = {},
    ): UploadResult {
        val slot = sessionManager.authorizeUpload(sessionId, fileId, token, sourceIp)
        onAuthorized()
        val meta = slot.meta

        val hashingSink = Sha256.hashingSink(fileSink.openWrite(meta))
        
        
        
        val bytes = try {
            hashingSink.buffer().use { buffered ->
                body.use { src -> TransferMeter(fileId).pump(src, buffered) }
            }
        } catch (e: Exception) {
            
            fileSink.discard(meta)
            
            
            
            
            if (e is TransferStorageFault) {
                throw ProtocolError.Forbidden(MigrationRejectReason.INSUFFICIENT_STORAGE.wire)
            }
            throw e
        }
        onBodyComplete()
        val actualSha = hashingSink.hash.hex()

        val expected = meta.sha256
        if (expected != null && !actualSha.equals(expected, ignoreCase = true)) {
            
            fileSink.finalizeFile(meta, expected) 
            throw ProtocolError.Internal("sha256 mismatch fileId=$fileId exp=$expected act=$actualSha")
        }

        val landed = fileSink.finalizeFile(meta, expected).getOrElse { e ->
            when (e) {
                
                is TransferStorageFault ->
                    throw ProtocolError.Forbidden(MigrationRejectReason.INSUFFICIENT_STORAGE.wire)
                
                
                is TransferCommittedUnconfirmed -> throw e
                else -> throw ProtocolError.Internal("finalize failed fileId=$fileId: ${e.message}")
            }
        }
        sessionManager.markDone(sessionId, fileId)
        return UploadResult(fileId = fileId, landedPath = landed, bytes = bytes, sha256 = actualSha, headers = fileSink.successHeaders(meta))
    }
}
