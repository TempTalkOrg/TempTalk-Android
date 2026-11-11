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





















class P2pContainerReceiveSink<R>(
    private val scope: CoroutineScope,
    private val spoolDir: File,
    private val onWriteProgress: (Long, Long) -> Unit = { _, _ -> },
    private val isSuccess: (R) -> Boolean = { true },
    private val onProgress: (Float) -> Unit = {},
    



    private val successHeaders: (R) -> Map<String, String> = { emptyMap() },
    



    private val onBodyComplete: () -> Unit = {},
    private val consume: suspend (InputStream) -> R,
) : FileSink {

    private val spool = ReceiveSpool(spoolDir, onWriteProgress = onWriteProgress)

    



    val result: CompletableDeferred<R> = CompletableDeferred()

    override fun openWrite(meta: FileMetadata): Sink {
        L.i { "[MigrationV2][P2P][Rx] body arriving fileId=${meta.id} declaredSize=${meta.size}" }
        
        
        val estimatedTotal = meta.size
        
        
        
        
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
        
        
        val sink = spool.openSink()
        if (estimatedTotal <= 0L) return sink
        return ProgressCountingSink(sink, estimatedTotal, onProgress)
    }

    




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

    




    
    
    @Suppress("BanRunBlockingOutsideTests")
    override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> {
        
        runCatching { onBodyComplete() }
            .onFailure { e -> L.w { "[MigrationV2][P2P][Rx] body-complete callback failed: ${e.message}" } }
        
        
        runCatching { spool.openSink().close() }
        return runCatching {
            
            
            val outcome = runBlocking { result.await() }
            if (!isSuccess(outcome)) {
                
                
                error("migration import did not complete successfully: $outcome")
            }
            replyHeaders = successHeaders(outcome)
            "verified outcome=$outcome"
        }
    }

    
    override fun discard(meta: FileMetadata) = abort()

    




    fun abort() {
        spool.abort(null)
    }

    @Volatile
    private var replyHeaders: Map<String, String> = emptyMap()

    override fun successHeaders(meta: FileMetadata): Map<String, String> = replyHeaders

    companion object {
        
        private const val PROGRESS_DELTA = 0.01f
    }
}
