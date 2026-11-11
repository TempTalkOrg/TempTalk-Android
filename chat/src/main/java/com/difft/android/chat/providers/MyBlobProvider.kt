package com.difft.android.chat.providers

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.difft.android.base.concurrent.AppExecutors
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.AppPrivateStorage
import com.difft.android.base.utils.FileUtil
import util.StreamUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Future

class MyBlobProvider private constructor() {

    fun forData(data: ByteArray): BlobBuilder = BlobBuilder(ByteArrayInputStream(data))

    fun forData(data: InputStream): BlobBuilder = BlobBuilder(data)

    @Synchronized
    @Throws(IOException::class)
    fun getStream(context: Context, uri: Uri): InputStream {
        val file = File(uri.path!!)
        if (!file.exists()) {
            throw IOException("File does not exist for URI: $uri")
        }
        return FileInputStream(file)
    }

    













    fun delete(uri: Uri): Boolean {
        val scheme = uri.scheme
        
        
        
        if (scheme != null && scheme != ContentResolver.SCHEME_FILE) {
            L.w { "[Blob] delete refused: unsupported scheme=$scheme" }
            return false
        }
        val path = uri.path
        if (path.isNullOrBlank()) {
            L.w { "[Blob] delete refused: uri has no path" }
            return false
        }
        if (!AppPrivateStorage.isAppPrivate(path)) {
            
            L.w { "[Blob] delete refused: path is outside app-private storage" }
            return false
        }
        val deleted = try {
            File(path).delete()
        } catch (e: SecurityException) {
            L.w { "[Blob] delete threw: ${e.javaClass.simpleName}" }
            false
        }
        if (!deleted) {
            L.w { "[Blob] delete returned false (already gone or denied)" }
        }
        return deleted
    }

    inner class BlobBuilder internal constructor(private val data: InputStream) {

        private val id: String = UUID.randomUUID().toString()
        private var mimeType: String? = null
        private var fileName: String? = null

        fun withMimeType(mimeType: String): BlobBuilder {
            this.mimeType = mimeType
            return this
        }

        fun withFileName(fileName: String?): BlobBuilder {
            this.fileName = fileName
            return this
        }

        @WorkerThread
        @Throws(IOException::class)
        fun createForDraftAttachmentAsync(context: Context): Future<Uri> {
            val outputFile = File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY), buildFileName(id))
            val outputStream: OutputStream = FileOutputStream(outputFile)
            val uri = buildUri(context)

            return CompletableFuture.supplyAsync({
                try {
                    StreamUtil.copy(data, outputStream)
                    uri
                } catch (e: IOException) {
                    
                    
                    delete(uri)
                    L.w(e) { "Error during write!" }
                    
                    
                    throw CompletionException(e)
                }
            }, AppExecutors.Default)
        }

        private fun buildFileName(id: String): String {
            var suffix = ""
            if (mimeType != null) {
                suffix = when (mimeType) {
                    "image/jpeg" -> ".jpg"
                    "image/png" -> ".png"
                    "video/mp4" -> ".mp4"
                    "audio/aac" -> ".aac"
                    else -> ".blob"
                }
            }
            return fileName ?: (id + suffix)
        }

        private fun buildUri(context: Context): Uri =
            Uri.fromFile(File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY), buildFileName(id)))
    }

    companion object {
        private val INSTANCE = MyBlobProvider()

        @JvmStatic
        fun getInstance(): MyBlobProvider = INSTANCE
    }
}
