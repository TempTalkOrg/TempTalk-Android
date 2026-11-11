package com.difft.android.base.utils

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Environment.MEDIA_MOUNTED
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.TextUtils
import android.webkit.MimeTypeMap
import androidx.annotation.VisibleForTesting
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.StorageNames
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.text.DecimalFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap






data class CopiedFileResult(
    val tempFile: File,
    val originalFileName: String
)


object FileUtil {

    const val FILE_DIR_GROUP_AVATAR = "group_avatar"
    const val FILE_DIR_AVATAR = "avatar"
    const val FILE_DIR_ATTACHMENT = "attachment"
    const val FILE_DIR_UPGRADE = "upgrade"
    const val DRAFT_ATTACHMENTS_DIRECTORY: String = "draft_blobs"

    
    const val SHARE_IMPORT_DIRECTORY: String = "share_import"
    const val FILE_DIR_GIF_FAVORITES = "gif_favorites"

    
    const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024

    
    private const val MTIME_GRANULARITY_SLACK_MS = 2_000L

    @JvmStatic
    fun canWriteToMediaStore(): Boolean {
        return Build.VERSION.SDK_INT > 28 ||
            PermissionUtil.arePermissionsGranted(
                application,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
    }

    
    
    
    private val fileValidityCache = ConcurrentHashMap<String, Boolean>()

    fun isFileValid(path: String): Boolean {
        if (fileValidityCache[path] == true) return true
        val file = File(path)
        val valid = file.exists() && file.isFile && file.length() > 0
        if (valid) fileValidityCache[path] = true
        return valid
    }

    






    fun invalidateFileValidity(path: String) {
        fileValidityCache.remove(path)
        fileValidityCache.remove("$path.encrypt")
    }

    
    fun invalidateFileValidityUnder(directoryPath: String) {
        fileValidityCache.keys.removeAll { it.startsWith(directoryPath) }
    }

    fun isFileNameValid(filename: String?): Boolean {
        
        if (filename.isNullOrEmpty() || filename.contains("..") || filename.contains("/")) {
            return false
        }
        return true
    }

    




    fun getFilePath(dir: String): String {
        val cachedDir = FilePathManager.getCachedDir(dir)
        if (cachedDir != null) {
            return cachedDir.absolutePath + File.separator
        }

        
        
        
        val baseDir = FilePathManager.getBaseDirFile()
        return File(baseDir, dir).absolutePath + File.separator
    }

    fun getAvatarCachePath(): String {
        return getFilePath(FILE_DIR_AVATAR)
    }

    fun getMessageAttachmentFilePath(messageId: String): String {
        return getFilePath(FILE_DIR_ATTACHMENT + File.separator + messageId)
    }

    fun deleteMessageFile(messageId: String) {
        val directoryPath = getFilePath(FILE_DIR_ATTACHMENT + File.separator + messageId)
        deleteFolder(File(directoryPath))
        
        
        invalidateFileValidityUnder(directoryPath)
    }

    private fun deleteFolder(folder: File): Boolean {
        if (folder.isDirectory) {
            val files = folder.listFiles()
            if (files != null) {
                for (file in files) {
                    deleteFolder(file)
                }
            }
        }

        return try {
            folder.delete()
        } catch (e: Exception) {
            L.e { "[FileUtil] delete folder error:${e.message}" }
            false
        }
    }

    fun clearAllFiles() {
        fileValidityCache.clear()
        clearAllFilesInternal(preserveLogs = false)
    }

    
    fun clearAllFilesExceptLogs() {
        fileValidityCache.clear()
        clearAllFilesInternal(preserveLogs = true)
    }

    private fun clearAllFilesInternal(preserveLogs: Boolean) {
        try {
            if (MEDIA_MOUNTED == Environment.getExternalStorageState()) {
                application.getExternalFilesDir(null)?.parentFile?.let { externalDir ->
                    if (externalDir.exists()) {
                        deleteDirectoryContents(externalDir, preserveLogs)
                    }
                }
            }

            application.dataDir?.let { dataDir ->
                if (dataDir.exists()) {
                    deleteDirectoryContents(dataDir, preserveLogs)
                }
            }
        } catch (e: Exception) {
            L.e { "[FileUtil] Error clearing files: ${e.message}" }
        }
    }

    



    private fun isLogFile(file: File): Boolean {
        if (file.isDirectory) return false
        val name = file.name
        return name.contains("_log_") && name.endsWith(".txt")
    }

    




    @VisibleForTesting
    internal fun isSecureConfigFile(file: File): Boolean {
        if (file.isDirectory) return false
        val parent = file.parentFile?.name ?: return false
        return (parent == "datastore" && file.name == StorageNames.SECURE_CONFIG_FILE) ||
            (parent == "shared_prefs" && file.name == "${StorageNames.SECURE_CONFIG_KEYSET_PREFS_FILE}.xml")
    }

    @VisibleForTesting
    internal fun deleteDirectoryContents(directory: File, preserveLogs: Boolean) {
        if (!directory.exists() || !directory.isDirectory) return

        directory.listFiles()?.forEach { file ->
            if (preserveLogs && isLogFile(file)) {
                L.d { "[FileUtil] Preserving log file: ${file.absolutePath}" }
                return@forEach
            }
            if (isSecureConfigFile(file)) return@forEach

            try {
                if (file.isDirectory) {
                    deleteDirectoryContents(file, preserveLogs)
                    
                    if (file.listFiles()?.isNotEmpty() == true) return@forEach
                }
                if (!file.delete()) {
                    L.w { "[FileUtil] Failed to delete file: ${file.absolutePath}, exists: ${file.exists()}, canRead: ${file.canRead()}, canWrite: ${file.canWrite()}" }
                }
            } catch (e: Exception) {
                L.e { "[FileUtil] Error deleting file ${file.absolutePath}: ${e.message}, exists: ${file.exists()}, canRead: ${file.canRead()}, canWrite: ${file.canWrite()}" }
            }
        }
    }

    fun readFile(file: File?): ByteArray? {
        var rf: RandomAccessFile? = null
        var data: ByteArray? = null
        try {
            rf = RandomAccessFile(file, "r")
            data = ByteArray(rf.length().toInt())
            rf.readFully(data)
        } catch (exception: Exception) {
            L.w { "[FileUtil] error: ${exception.stackTraceToString()}" }
        } finally {
            closeQuietly(rf)
        }
        return data
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (exception: Exception) {
            L.w { "[FileUtil] error: ${exception.stackTraceToString()}" }
        }
    }

    













    private val progressMap = ConcurrentHashMap<String, Int>()

    
    private val terminalAtMap = ConcurrentHashMap<String, Long>()

    private const val TERMINAL_RETENTION_MS = 30_000L
    private const val SWEEP_THRESHOLD = 256

    
    @VisibleForTesting
    internal var progressClock: () -> Long = { System.currentTimeMillis() }

    private val _progressUpdate = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val progressUpdate: SharedFlow<String> = _progressUpdate

    private fun isEvictableProgress(progress: Int): Boolean = progress == 100

    fun emitProgressUpdate(id: String, progress: Int) {
        progressMap[id] = progress
        if (isEvictableProgress(progress)) {
            terminalAtMap[id] = progressClock()
            if (progressMap.size > SWEEP_THRESHOLD) sweepExpiredTerminalProgress()
        } else {
            
            
            terminalAtMap.remove(id)
        }
        _progressUpdate.tryEmit(id)
    }

    fun getProgress(id: String): Int? {
        evictTerminalProgressIfExpired(id)
        return progressMap[id]
    }

    private fun evictTerminalProgressIfExpired(id: String) {
        val terminalAt = terminalAtMap[id] ?: return
        if (progressClock() - terminalAt < TERMINAL_RETENTION_MS) return
        terminalAtMap.remove(id)
        
        
        progressMap.computeIfPresent(id) { _, value -> if (isEvictableProgress(value)) null else value }
    }

    private fun sweepExpiredTerminalProgress() {
        terminalAtMap.keys.toList().forEach { evictTerminalProgressIfExpired(it) }
    }

    private val downloadingMap = hashMapOf<Long, String>()

    fun addToDownloadingMap(id: Long, name: String) {
        downloadingMap[id] = name
    }

    fun removeFormDownloadingMap(id: Long) {
        downloadingMap.remove(id)
    }

    fun isDownLoading(name: String): Boolean {
        return downloadingMap.containsValue(name)
    }

    fun getFilePath(downloadId: Long): String? {
        return downloadingMap[downloadId]
    }

    fun getFileSize(uri: Uri): Long {
        runCatching {
            application.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    val size = cursor.getLong(sizeIndex)
                    if (size > 0) return size
                }
            }
        }

        runCatching {
            application.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (pfd.statSize > 0) return pfd.statSize
            }
        }

        runCatching {
            application.contentResolver.openInputStream(uri)?.use { input ->
                var size = 0L
                val buffer = ByteArray(4096)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    size += read
                    
                    
                    if (size >= MAX_SUPPORT_FILE_SIZE) return size
                }
                return size
            }
        }

        return -1L
    }

    const val MAX_SUPPORT_FILE_SIZE = 1024 * 1024 * 200

    
    const val SHARE_IMPORT_QUOTA_BYTES: Long = 2L * MAX_SUPPORT_FILE_SIZE

    
    fun remainingStagingBytes(dir: File, quota: Long = SHARE_IMPORT_QUOTA_BYTES): Long =
        quota - dir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    fun readableFileSize(size: Long): String {
        if (size <= 0) return "0"
        val units = arrayOf("B", "kB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble()))
            .toString() + " " + units[digitGroups]
    }

    fun getMimeTypeType(uri: Uri): String? {
        return application.contentResolver.getType(uri)
    }

    



    fun copyUriToFile(uri: Uri): CopiedFileResult? {
        return runCatching {
            val contentResolver = application.contentResolver
            val originalFileName = getFileNameFromUri(uri, contentResolver)
                ?: generateFileNameFromMimeType(uri, contentResolver)

            val extension = originalFileName.substringAfterLast('.', "tmp")
            val tempFileName = "${UUID.randomUUID()}.$extension"

            val directory = File(getFilePath(DRAFT_ATTACHMENTS_DIRECTORY))
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val tempFile = File(directory, tempFileName)
            if (!copyUriContent(uri, tempFile, contentResolver)) {
                tempFile.delete()
                throw FileNotFoundException("Unable to open content for uri")
            }
            CopiedFileResult(tempFile, originalFileName)
        }.onFailure {
            L.e { "copyUriToFile fail:" + it.stackTraceToString() }
        }.getOrNull()
    }

    









    private fun copyUriContent(uri: Uri, target: File, resolver: ContentResolver): Boolean {
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
                return true
            }
        }.onFailure { L.w { "[FileUtil] openInputStream failed, trying fd fallback: ${it.message}" } }

        runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                
                
                
                
                
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                    return true
                }
            }
        }.onFailure { L.w { "[FileUtil] openFileDescriptor fallback failed: ${it.message}" } }

        return false
    }

    private fun getFileNameFromUri(uri: Uri, contentResolver: ContentResolver): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else null
        }
    }

    private fun generateFileNameFromMimeType(uri: Uri, contentResolver: ContentResolver): String {
        val extension = getMimeTypeType(uri)?.let {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        } ?: "tmp"
        return "${System.currentTimeMillis()}.$extension"
    }

    fun checkUriPathSecure(context: Context, uri: Uri): Boolean {
        val dataDir = context.applicationInfo.dataDir
        val path = uri.path
        if (!TextUtils.isEmpty(dataDir) && !TextUtils.isEmpty(path)) {
            
            if (path!!.contains("../") || path.contains(dataDir)) {
                L.e { "GetFilePathFromUri" + "checkUriPathSecure check result is false." }
                return false
            }
        }
        return true
    }

    fun deleteTempFile(fileName: String?) {
        fileName?.let {
            val tempFile = File(getFilePath(DRAFT_ATTACHMENTS_DIRECTORY), it)
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }


    






    fun clearDraftAttachmentsDirectory() {
        try {
            val processStartWallMs = System.currentTimeMillis() -
                (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            
            
            
            val staleBeforeMs = processStartWallMs - MTIME_GRANULARITY_SLACK_MS
            val draftDir = FilePathManager.draftAttachmentsDir
            if (draftDir.exists()) {
                val stale = draftDir.listFiles().orEmpty()
                    .filter { it.isFile && it.lastModified() < staleBeforeMs }
                stale.forEach { it.delete() }
                L.i { "[FileUtil] draft_blobs sweep deleted=${stale.size}" }
            }

            val shareImportDir = FilePathManager.shareImportDir
            if (shareImportDir.exists()) {
                val stale = shareImportDir.listFiles().orEmpty()
                    .filter { it.isFile && it.lastModified() < staleBeforeMs }
                stale.forEach { it.delete() }
                L.i { "[FileUtil] share_import sweep deleted=${stale.size}" }
            }

            
            val legacyTempDir = File(draftDir.parentFile, "temp_attachments")
            if (legacyTempDir.exists()) {
                legacyTempDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.delete()
                    }
                }
                legacyTempDir.delete()
                L.i { "[FileUtil] Cleared legacy temp_attachments directory" }
            }
        } catch (e: Exception) {
            L.e { "[FileUtil] Error clearing temp directories: ${e.message}" }
        }
    }

    fun deleteMessageAttachmentEmptyDirectories() {
        try {
            val attachmentDir = File(getFilePath(FILE_DIR_ATTACHMENT))
            if (attachmentDir.exists()) {
                deleteEmptyDirectoriesRecursively(attachmentDir)
            }
        } catch (e: Exception) {
            L.e { "[FileUtil] Error deleting empty directories: ${e.message}" }
        }
    }

    private fun deleteEmptyDirectoriesRecursively(directory: File) {
        if (!directory.exists() || !directory.isDirectory) return

        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteEmptyDirectoriesRecursively(file)
            }
        }

        val files = directory.listFiles()
        if (files == null || files.isEmpty()) {
            try {
                if (!directory.delete()) {
                    L.w { "[FileUtil] Failed to delete empty directory: ${directory.absolutePath}" }
                }
            } catch (e: Exception) {
                L.e { "[FileUtil] Error deleting empty directory ${directory.absolutePath}: ${e.message}" }
            }
        }
    }
}