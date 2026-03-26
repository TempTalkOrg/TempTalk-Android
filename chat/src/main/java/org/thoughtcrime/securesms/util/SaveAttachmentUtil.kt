package org.thoughtcrime.securesms.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.providers.MyBlobProvider
import util.StreamUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object SaveAttachmentUtil {

    private const val TAG = "SaveAttachmentUtil"

    data class Attachment(
        val uri: Uri,
        val contentType: String,
        val date: Long,
        val fileName: String? = null,
        val shouldDeleteOriginalFile: Boolean = false,
        val shouldShowToast: Boolean = true
    )

    sealed class SaveResult {
        data class Success(val attachment: Attachment?) : SaveResult()
        data object WriteAccessFailure : SaveResult()
        data object Failure : SaveResult()
    }

    suspend fun saveAttachment(context: Context, attachment: Attachment): SaveResult =
        withContext(Dispatchers.IO) {
            try {
                if (!FileUtil.canWriteToMediaStore()) {
                    L.w { "$TAG save attachment failed: no write access to media store" }
                    return@withContext SaveResult.WriteAccessFailure
                }
                val directory = saveAttachmentInternal(context, attachment)
                if (directory == null) {
                    L.w { "$TAG save attachment failed: IO returned null, contentType=${attachment.contentType}" }
                    SaveResult.Failure
                } else {
                    SaveResult.Success(attachment)
                }
            } catch (e: IOException) {
                L.w(e) { "$TAG save attachment failed, contentType=${attachment.contentType}" }
                SaveResult.Failure
            }
        }

    suspend fun saveAttachments(context: Context, attachments: List<Attachment>): SaveResult =
        withContext(Dispatchers.IO) {
            try {
                if (!FileUtil.canWriteToMediaStore()) {
                    L.w { "$TAG save attachments failed: no write access to media store" }
                    return@withContext SaveResult.WriteAccessFailure
                }
                val nameCache = HashMap<Uri, HashSet<String>>()
                for (attachment in attachments) {
                    val directory = saveAttachmentInternal(context, attachment, nameCache)
                    if (directory == null) {
                        L.w { "$TAG save attachments failed at contentType=${attachment.contentType}" }
                        return@withContext SaveResult.Failure
                    }
                }
                L.i { "$TAG save ${attachments.size} attachments success" }
                if (attachments.size > 1) {
                    SaveResult.Success(null)
                } else {
                    SaveResult.Success(attachments.firstOrNull())
                }
            } catch (e: IOException) {
                L.w(e) { "$TAG save attachments failed, count=${attachments.size}" }
                SaveResult.Failure
            }
        }

    suspend fun saveWithUI(context: Context, attachment: Attachment) {
        ComposeDialogManager.showWait(context)
        try {
            val result = saveAttachment(context, attachment)
            showResultToast(context, result)
        } finally {
            ComposeDialogManager.dismissWait()
        }
    }

    @JvmStatic
    fun saveWithUIFromJava(context: Context, lifecycleOwner: LifecycleOwner, attachment: Attachment) {
        lifecycleOwner.lifecycleScope.launch {
            saveWithUI(context, attachment)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun showWarningDialog(context: Context, onAccept: () -> Unit, count: Int = 1) {
        ComposeDialogManager.showMessageDialogForJava(
            context,
            context.getString(R.string.ConversationFragment_save_to_sd_card),
            context.resources.getQuantityString(
                R.plurals.ConversationFragment_saving_n_media_to_storage_warning,
                count,
                count
            ),
            context.getString(R.string.chat_dialog_ok),
            context.getString(R.string.chat_dialog_cancel),
            true,
            false,
            { onAccept() },
            null,
            null
        )
    }

    private fun showResultToast(context: Context, result: SaveResult) {
        when (result) {
            is SaveResult.Failure -> {
                ToastUtil.show(context.getString(R.string.ConversationFragment_error_while_saving_attachments_to_sd_card))
            }
            is SaveResult.Success -> {
                val attachment = result.attachment
                if (attachment != null && attachment.shouldShowToast) {
                    when {
                        attachment.contentType.startsWith("video/") ||
                            attachment.contentType.startsWith("image/") ->
                            ToastUtil.show(R.string.SaveAttachmentTask_saved_to_album)
                        attachment.contentType.startsWith("audio/") ->
                            ToastUtil.show(R.string.SaveAttachmentTask_saved_to_audio)
                        else ->
                            ToastUtil.show(R.string.SaveAttachmentTask_saved_to_downloads)
                    }
                } else if (attachment == null) {
                    ToastUtil.show(R.string.SaveAttachmentTask_saved_to_downloads)
                }
            }
            is SaveResult.WriteAccessFailure -> {
                ToastUtil.show(R.string.ConversationFragment_unable_to_write_to_sd_card_exclamation)
            }
        }
    }

    // ---- Internal save logic (migrated from SaveAttachmentTask) ----

    private fun saveAttachmentInternal(
        context: Context,
        attachment: Attachment,
        nameCache: HashMap<Uri, HashSet<String>> = HashMap()
    ): String? {
        val contentType = requireNotNull(MediaUtil.getCorrectedMimeType(attachment.contentType))
        var fileName = attachment.fileName ?: generateOutputFileName(contentType, attachment.date)
        fileName = sanitizeOutputFileName(fileName)

        val result = createMediaUri(context, getMediaStoreContentUriForType(contentType), contentType, fileName, nameCache)
        val updateValues = ContentValues()
        val mediaUri = result.mediaUri ?: return null

        PartAuthority.getAttachmentStream(context, attachment.uri)?.use { inputStream ->
            if (result.outputUri.scheme == ContentResolver.SCHEME_FILE) {
                FileOutputStream(mediaUri.path).use { outputStream ->
                    StreamUtil.copy(inputStream, outputStream)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(mediaUri.path),
                        arrayOf(contentType),
                        null
                    )
                }
            } else {
                context.contentResolver.openOutputStream(mediaUri, "w")?.use { outputStream ->
                    val total = StreamUtil.copy(inputStream, outputStream)
                    if (total > 0) {
                        updateValues.put(MediaStore.MediaColumns.SIZE, total)
                    }
                }
            }
            if (attachment.shouldDeleteOriginalFile) {
                MyBlobProvider.getInstance().delete(attachment.uri)
            }
        } ?: return null

        if (Build.VERSION.SDK_INT > 28) {
            updateValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
        }

        if (updateValues.size() > 0) {
            context.contentResolver.update(mediaUri, updateValues, null, null)
        }

        L.i { "$TAG save attachment success, contentType=$contentType, fileName=$fileName" }
        return result.outputUri.lastPathSegment
    }

    private fun getMediaStoreContentUriForType(contentType: String): Uri {
        return when {
            contentType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            contentType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            contentType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> getDownloadUri()
        }
    }

    private fun getDownloadUri(): Uri {
        return if (Build.VERSION.SDK_INT < 29) {
            Uri.fromFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
    }

    private fun createMediaUri(
        context: Context,
        outputUri: Uri,
        contentType: String,
        fileName: String,
        nameCache: HashMap<Uri, HashSet<String>>
    ): CreateMediaUriResult {
        val fileParts = getFileNameParts(fileName)
        val base = fileParts[0]
        val extension = fileParts[1]
        var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

        if (MediaUtil.isOctetStream(mimeType) && MediaUtil.isImageVideoOrAudioType(contentType)) {
            L.d { "$TAG MimeTypeMap returned octet stream for media, changing to provided content type [$contentType] instead." }
            mimeType = contentType
        }

        if (MediaUtil.isOctetStream(mimeType)) {
            mimeType = when (outputUri) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI -> "audio/*"
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> "video/*"
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI -> "image/*"
                else -> mimeType
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.DATE_ADDED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            put(MediaStore.MediaColumns.DATE_MODIFIED, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
        }

        if (Build.VERSION.SDK_INT > 28) {
            var i = 0
            var displayName = fileName
            while (pathInCache(nameCache, outputUri, displayName) || displayNameTaken(context, outputUri, displayName)) {
                displayName = "$base-${++i}.$extension"
            }
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
            putInCache(nameCache, outputUri, displayName)
        } else if (outputUri.scheme == ContentResolver.SCHEME_FILE) {
            val outputDirectory = File(outputUri.path!!)
            var outputFile = File(outputDirectory, "$base.$extension")
            var i = 0
            while (pathInCache(nameCache, outputUri, outputFile.path) || outputFile.exists()) {
                outputFile = File(outputDirectory, "$base-${++i}.$extension")
            }
            if (outputFile.isHidden) {
                throw IOException("Specified name would not be visible")
            }
            putInCache(nameCache, outputUri, outputFile.path)
            return CreateMediaUriResult(outputUri, Uri.fromFile(outputFile))
        } else {
            val dir = getExternalPathForType(contentType)
                ?: throw IOException(String.format(Locale.US, "Path for type: %s was not available", contentType))

            var outputFileName = fileName
            var dataPath = "$dir/$outputFileName"
            var i = 0
            while (pathInCache(nameCache, outputUri, dataPath) || pathTaken(context, outputUri, dataPath)) {
                L.d { "$TAG The content exists. Rename and check again." }
                outputFileName = "$base-${++i}.$extension"
                dataPath = "$dir/$outputFileName"
            }
            putInCache(nameCache, outputUri, outputFileName)
            @Suppress("DEPRECATION")
            contentValues.put(MediaStore.MediaColumns.DATA, dataPath)
        }

        return try {
            CreateMediaUriResult(outputUri, context.contentResolver.insert(outputUri, contentValues))
        } catch (e: RuntimeException) {
            if (e is IllegalArgumentException || e.cause is IllegalArgumentException) {
                L.w { "$TAG Unable to create uri in $outputUri with mimeType [$mimeType]" }
                val downloadUri = getDownloadUri()
                CreateMediaUriResult(downloadUri, context.contentResolver.insert(downloadUri, contentValues))
            } else {
                throw e
            }
        }
    }

    private fun generateOutputFileName(contentType: String, timestamp: Long): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType) ?: "attach"
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        val appName = PackageUtil.getAppName() ?: "attachment"
        return "$appName-${dateFormatter.format(timestamp)}.$extension"
    }

    private fun sanitizeOutputFileName(fileName: String): String {
        return File(fileName).name
    }

    private fun getExternalPathForType(contentType: String): String? {
        var storage: File? = when {
            contentType.startsWith("video/") ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            contentType.startsWith("audio/") ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            contentType.startsWith("image/") ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            else -> null
        }
        storage = ensureExternalPath(storage)
        return storage?.absolutePath
    }

    private fun ensureExternalPath(path: File?): File? {
        if (path != null && path.exists()) return path
        if (path == null) {
            val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            return if (documents.exists() || documents.mkdirs()) documents else null
        }
        return if (path.mkdirs()) path else null
    }

    private fun putInCache(cache: HashMap<Uri, HashSet<String>>, outputUri: Uri, dataPath: String) {
        val pathSet = cache.getOrPut(outputUri) { HashSet() }
        if (!pathSet.add(dataPath)) {
            throw IllegalStateException("Path already used in data set.")
        }
    }

    private fun pathInCache(cache: HashMap<Uri, HashSet<String>>, outputUri: Uri, dataPath: String): Boolean {
        return cache[outputUri]?.contains(dataPath) == true
    }

    private fun pathTaken(context: Context, outputUri: Uri, dataPath: String): Boolean {
        @Suppress("DEPRECATION")
        context.contentResolver.query(
            outputUri,
            arrayOf(MediaStore.MediaColumns.DATA),
            MediaStore.MediaColumns.DATA + " = ?",
            arrayOf(dataPath),
            null
        )?.use { cursor ->
            return cursor.moveToFirst()
        } ?: throw IOException("Something is wrong with the filename to save")
    }

    private fun displayNameTaken(context: Context, outputUri: Uri, displayName: String): Boolean {
        context.contentResolver.query(
            outputUri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
            arrayOf(displayName),
            null
        )?.use { cursor ->
            return cursor.moveToFirst()
        } ?: throw IOException("Something is wrong with the displayName to save")
    }

    private fun getFileNameParts(fileName: String): Array<String> {
        val tokens = fileName.split("\\.(?=[^.]+$)".toRegex())
        return arrayOf(
            tokens[0],
            if (tokens.size > 1) tokens[1] else ""
        )
    }

    private class CreateMediaUriResult(val outputUri: Uri, val mediaUri: Uri?)
}
