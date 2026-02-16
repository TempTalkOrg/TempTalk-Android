package com.difft.android.base.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Environment.MEDIA_MOUNTED
import android.provider.OpenableColumns
import android.text.TextUtils
import android.webkit.MimeTypeMap
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.text.DecimalFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of copying a Uri to a temp file.
 * @param tempFile The temporary file with UUID-based name
 * @param originalFileName The original file name from the Uri
 */
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

    /** Large file threshold for manual download prompt (10MB) */
    const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024


    // Cache positive file validity results to avoid repeated File.exists() I/O
    // during RecyclerView scrolling. Only caches true; false results are not cached
    // so that download completion is naturally picked up on next check.
    private val fileValidityCache = ConcurrentHashMap<String, Boolean>()

    fun isFileValid(path: String): Boolean {
        if (fileValidityCache[path] == true) return true
        val file = File(path)
        val valid = file.exists() && file.isFile && file.length() > 0
        if (valid) fileValidityCache[path] = true
        return valid
    }

    fun isFileNameValid(filename: String?): Boolean {
        // prevent directory traversal and ensure filename doesn't contain path separators
        if (filename.isNullOrEmpty() || filename.contains("..") || filename.contains("/")) {
            return false
        }
        return true
    }

    /**
     * Get file path for directory
     *
     * For common directories (avatar, group_avatar, attachment, upgrade, draft_blobs):
     *   - Returns cached File path (initialized once lazily, thread-safe)
     *   - Safe to call from main thread after first access
     *   - No repeated getExternalFilesDir() calls = no ANR risk
     *
     * For dynamic/uncommon directories:
     *   - Falls back to baseDir + subdir construction
     *   - Returns path only, does NOT create directory (caller must ensure directory exists before writing)
     *   - Safe to call from main thread (no IO operations)
     */
    fun getFilePath(dir: String): String {
        // Try to get cached directory first for common paths
        val cachedDir = FilePathManager.getCachedDir(dir)
        if (cachedDir != null) {
            return cachedDir.absolutePath + File.separator
        }

        // Fallback for dynamic/uncommon paths (e.g., "attachment/messageId")
        // Only return path, do NOT check exists() or call mkdirs() here to avoid main thread IO
        // Directory creation should be done by the caller before writing files
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
        deleteFolder(File(getFilePath(FILE_DIR_ATTACHMENT + File.separator + messageId)))
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

    /**
     * Clear all files except log files.
     * Log files match pattern: {packageName}_log_{date}.txt
     * e.g., org.difft.chative.test_log_20260112.txt
     */
    fun clearAllFilesExceptLogs() {
        fileValidityCache.clear()
        clearAllFilesInternal(preserveLogs = true)
    }

    private fun clearAllFilesInternal(preserveLogs: Boolean) {
        try {
            // Clear external storage files if available
            if (MEDIA_MOUNTED == Environment.getExternalStorageState()) {
                application.getExternalFilesDir(null)?.parentFile?.let { externalDir ->
                    if (externalDir.exists()) {
                        deleteDirectoryContents(externalDir, preserveLogs)
                    }
                }
            }

            // Clear app private data directory (data/data/package_name)
            application.dataDir?.let { dataDir ->
                if (dataDir.exists()) {
                    deleteDirectoryContents(dataDir, preserveLogs)
                }
            }
        } catch (e: Exception) {
            L.e { "[FileUtil] Error clearing files: ${e.message}" }
        }
    }

    /**
     * Check if a file is a log file that should be preserved.
     * Log file pattern: {packageName}_log_{date}.txt
     */
    private fun isLogFile(file: File): Boolean {
        if (file.isDirectory) return false
        val name = file.name
        // Pattern: {packageName}_log_{yyyyMMdd}.txt
        return name.contains("_log_") && name.endsWith(".txt")
    }

    private fun deleteDirectoryContents(directory: File, preserveLogs: Boolean) {
        if (!directory.exists() || !directory.isDirectory) return

        directory.listFiles()?.forEach { file ->
            // Skip log files if preserveLogs is true
            if (preserveLogs && isLogFile(file)) {
                L.d { "[FileUtil] Preserving log file: ${file.absolutePath}" }
                return@forEach
            }

            try {
                if (file.isDirectory) {
                    deleteDirectoryContents(file, preserveLogs)
                }
                if (!file.delete()) {
                    L.w { "[FileUtil] Failed to delete file: ${file.absolutePath}, exists: ${file.exists()}, canRead: ${file.canRead()}, canWrite: ${file.canWrite()}" }
                }
            } catch (e: Exception) {
                L.e { "[FileUtil] Error deleting file ${file.absolutePath}: ${e.message}, exists: ${file.exists()}, canRead: ${file.canRead()}, canWrite: ${file.canWrite()}" }
            }
        }
    }

    //file文件读取成byte[]
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

    //关闭读取file
    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (exception: Exception) {
            L.w { "[FileUtil] error: ${exception.stackTraceToString()}" }
        }
    }

    private val progressMap = hashMapOf<String, Int>()

    private val _progressUpdate = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val progressUpdate: SharedFlow<String> = _progressUpdate

    fun emitProgressUpdate(id: String, progress: Int) {
        progressMap[id] = progress
        _progressUpdate.tryEmit(id)
    }

    fun getProgress(id: String): Int? {
        return progressMap[id]
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
        // Strategy 1: ContentResolver query
        runCatching {
            application.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    val size = cursor.getLong(sizeIndex)
                    if (size > 0) return size
                }
            }
        }

        // Strategy 2: openFileDescriptor (fast fallback)
        runCatching {
            application.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (pfd.statSize > 0) return pfd.statSize
            }
        }

        // Strategy 3: Stream reading (last resort, inspired by Signal)
        runCatching {
            application.contentResolver.openInputStream(uri)?.use { input ->
                var size = 0L
                val buffer = ByteArray(4096)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    size += read
                }
                return size
            }
        }

        return -1L
    }

    const val MAX_SUPPORT_FILE_SIZE = 1024 * 1024 * 200 //200M

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

    /**
     * Copy Uri to a temp file with UUID-based name to avoid conflicts.
     * @return CopiedFileResult containing the temp file and original file name, or null if failed
     */
    fun copyUriToFile(uri: Uri): CopiedFileResult? {
        return runCatching {
            val contentResolver = application.contentResolver
            val originalFileName = getFileNameFromUri(uri, contentResolver)
                ?: generateFileNameFromMimeType(uri, contentResolver)

            // Extract extension from original file name
            val extension = originalFileName.substringAfterLast('.', "tmp")
            val tempFileName = "${UUID.randomUUID()}.$extension"

            val directory = File(getFilePath(DRAFT_ATTACHMENTS_DIRECTORY))
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val tempFile = File(directory, tempFileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            CopiedFileResult(tempFile, originalFileName)
        }.onFailure {
            L.e { "copyUriToFile fail:" + it.stackTraceToString() }
        }.getOrNull()
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
            //检测path存在路径穿越、检测路径是否指向APP自身私有存储路径
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


    /**
     * Clear temp directories on startup.
     * These directories are used for intermediate files during attachment processing.
     * After app restart, any files here are orphaned and can be safely deleted.
     */
    fun clearDraftAttachmentsDirectory() {
        try {
            // Clear current draft_blobs directory
            val draftDir = FilePathManager.draftAttachmentsDir
            if (draftDir.exists()) {
                draftDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.delete()
                    }
                }
                L.i { "[FileUtil] Cleared draft_blobs directory" }
            }

            // Clear legacy temp_attachments directory (for users upgrading from older versions)
            val legacyTempDir = File(draftDir.parentFile, "temp_attachments")
            if (legacyTempDir.exists()) {
                legacyTempDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.delete()
                    }
                }
                // Try to delete the directory itself if empty
                legacyTempDir.delete()
                L.i { "[FileUtil] Cleared legacy temp_attachments directory" }
            }
        } catch (e: Exception) {
            L.e { "[FileUtil] Error clearing temp directories: ${e.message}" }
        }
    }

    /**
     * Deletes empty directories in the message attachment directory.
     */
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

        // First recursively delete empty directories in subdirectories
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteEmptyDirectoriesRecursively(file)
            }
        }

        // Then check if current directory is empty and delete it
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