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

    /**
     * Deletes a blob owned by this provider. Ownership is structural: a file-scheme uri whose path
     * resolves inside an app-private root. A uri outside that domain is REFUSED rather than
     * attempted, so shared-storage media stays untouched by construction instead of by a delete
     * that merely happens to fail — and whose failure nobody could observe.
     *
     * The gate lives here and nowhere else: callers pass the uri they own, this is the only place
     * that decides whether the path belongs to us.
     *
     * No `exists()` pre-check: `File.delete()` already reports false for a missing file, so the
     * probe only bought an extra IO round trip while letting existence take part in the decision.
     *
     * @return true only when a file was actually removed.
     */
    fun delete(uri: Uri): Boolean {
        val scheme = uri.scheme
        // A null scheme is legal input: callers build uris straight from bare absolute paths. Only
        // schemes that clearly belong to someone else are refused, which also keeps a content uri
        // out of File(): its path is a pseudo path such as /external/images/media/123.
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
            // Deliberately without the path: a shared-storage path carries the user's own file name.
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
                    // Delegates to the outer delete so the write-failure cleanup path gets the same
                    // ownership gate and the same failure log as every other caller.
                    delete(uri)
                    L.w(e) { "Error during write!" }
                    // Wrap so Future.get() callers still see the original IOException
                    // via ExecutionException.getCause().
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
