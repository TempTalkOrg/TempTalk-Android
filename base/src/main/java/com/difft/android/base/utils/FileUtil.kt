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
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.text.DecimalFormat


object FileUtil {

    const val FILE_DIR_GROUP_AVATAR = "group_avatar"
    const val FILE_DIR_AVATAR = "avatar"
    const val FILE_DIR_ATTACHMENT = "attachment"
    const val FILE_DIR_UPGRADE = "upgrade"
    const val DRAFT_ATTACHMENTS_DIRECTORY: String = "draft_blobs"
    const val TEMP_ATTACHMENTS_DIRECTORY: String = "temp_attachments"


    fun isFileValid(path: String): Boolean {
        // prevent directory traversal attacks by checking for ".." in path
        if (path.contains("..")) {
            return false
        }
        val file = File(path)
        return file.exists() && file.isFile && file.length() > 0
    }

    fun isFileNameValid(filename: String?): Boolean {
        // prevent directory traversal and ensure filename doesn't contain path separators
        if (filename.isNullOrEmpty() || filename.contains("..") || filename.contains("/")) {
            return false
        }
        return true
    }

    fun getFilePath(dir: String): String {
        val directoryPath = if (MEDIA_MOUNTED == Environment.getExternalStorageState()) { //判断外部存储是否可用
            application.getExternalFilesDir(dir)?.absolutePath
        } else { //没外部存储就使用内部存储
            application.filesDir.absolutePath + File.separator + dir
        }
        val file = directoryPath?.let { File(it) }
        if (file?.exists() == false) { //判断文件目录是否存在
            file.mkdirs()
        }
        return directoryPath + File.separator
    }

    fun getAvatarCachePath(): String {
        return getFilePath(FILE_DIR_AVATAR)
    }

    fun getMessageAttachmentFilePath(messageId: String): String? {
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
        try {
            // Clear external storage files if available
            if (MEDIA_MOUNTED == Environment.getExternalStorageState()) {
                application.getExternalFilesDir(null)?.parentFile?.let { externalDir ->
                    if (externalDir.exists()) {
                        deleteDirectoryContents(externalDir)
                    }
                }
            }

            // Clear app private data directory (data/data/package_name)
            application.dataDir?.let { dataDir ->
                if (dataDir.exists()) {
                    deleteDirectoryContents(dataDir)
                }
            }
        } catch (e: Exception) {
            L.e { "[FileUtil] Error clearing files: ${e.message}" }
        }
    }

    private fun deleteDirectoryContents(directory: File) {
        if (!directory.exists() || !directory.isDirectory) return

        directory.listFiles()?.forEach { file ->
            try {
                if (file.isDirectory) {
                    deleteDirectoryContents(file)
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
            exception.printStackTrace()
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
            exception.printStackTrace()
        }
    }

    val progressMap = hashMapOf<String, Int>()

    private val mProgressUpdateSubject = BehaviorSubject.create<String>()
    fun emitProgressUpdate(id: String, progress: Int) {
        progressMap[id] = progress
        mProgressUpdateSubject.onNext(id)
    }

//    fun removeProgress(id: String) {
//        progressMap.remove(id)
//        mProgressUpdateSubject.onNext(id)
//    }

    val progressUpdate: Observable<String> = mProgressUpdateSubject

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
        var fileSize: Long = -1
        application.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                fileSize = cursor.getLong(sizeIndex)
            }
        }
        return fileSize
    }

    const val MAX_SUPPORT_FILE_SIZE = 1024 * 1024 * 200

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

    fun copyUriToFile(uri: Uri): File? {
        return runCatching {
            val contentResolver = application.contentResolver
            val fileName = getFileNameFromUri(uri, contentResolver)
                ?: generateFileNameFromMimeType(uri, contentResolver)
            val directory = File(getFilePath(TEMP_ATTACHMENTS_DIRECTORY))
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val file = File(directory, fileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            file
        }.onFailure {
            it.printStackTrace()
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
            val tempFile = File(getFilePath(TEMP_ATTACHMENTS_DIRECTORY), it)
            if (tempFile.exists()) {
                tempFile.delete()
            }
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