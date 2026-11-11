package com.difft.android.migration.v2.transfer

import com.difft.android.migration.v2.container.ArchiveSink
import com.difft.android.p2p.engine.io.FileSource
import okio.Pipe
import okio.Source
import okio.buffer
import java.io.OutputStream















class P2pContainerPipe(
    private val declaredLength: Long,
    private val fileName: String = "migration.ttmig",
    bufferBytes: Long = DEFAULT_PIPE_BUFFER,
) {
    private val pipe = Pipe(bufferBytes)

    




    val archiveSink: ArchiveSink = object : ArchiveSink {
        override fun openStream(): OutputStream = pipe.sink.buffer().outputStream()
        override fun abort() {
            runCatching { pipe.sink.close() }
        }
    }

    





    val fileSource: FileSource = object : FileSource {
        override val fileName: String = this@P2pContainerPipe.fileName
        override val size: Long = declaredLength
        override val mimeType: String = "application/octet-stream"
        override val sha256: String? = null 
        override fun openRead(): Source = pipe.source
    }

    




    fun abortRead() {
        runCatching { pipe.source.close() }
    }

    companion object {
        
        const val DEFAULT_PIPE_BUFFER: Long = 1L * 1024 * 1024

        
        const val UNKNOWN_LENGTH: Long = -1L
    }
}
