package com.difft.android.network.requests

import com.difft.android.base.log.lumberjack.L
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.Source
import okio.source
import java.io.File

class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType? = null,
    private val progressListener: ProgressListener? = null
) : RequestBody() {

    override fun contentType(): MediaType? {
        return contentType
    }

    override fun contentLength(): Long {
        return file.length()
    }

    override fun writeTo(sink: BufferedSink) {
        var source: Source? = null
        try {
            source = file.source()
            val buffer = Buffer()
            var totalBytesRead: Long = 0
            var bytesRead: Long
            while (source.read(buffer, 8192).also { bytesRead = it } != -1L) {
                sink.write(buffer, bytesRead)
                totalBytesRead += bytesRead
                val progress = (100.0 * totalBytesRead / contentLength()).toInt()
                L.d { "[UploadAttachmentJob]===upload file======$totalBytesRead" + "===" + contentLength() + "===" + progress + "%" }
                progressListener?.onProgress(totalBytesRead, contentLength(), progress)
            }
        } finally {
            source?.close()
        }
    }
}

interface ProgressListener {
    fun onProgress(bytesRead: Long, contentLength: Long, progress: Int)
}
