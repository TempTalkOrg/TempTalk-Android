package com.difft.android.selector.utils

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.config.FileSizeUnit
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectMimeType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

object PictureFileUtils {
    private const val BYTE_SIZE = 1024
    private const val POSTFIX_JPG = ".jpg"
    private const val POSTFIX_MP4 = ".mp4"
    private const val POSTFIX_AMR = ".amr"

    @JvmStatic
    fun createCameraFile(context: Context, chooseMode: Int, fileName: String?, format: String?, outCameraDirectory: String?): File {
        return createMediaFile(context, chooseMode, fileName, format, outCameraDirectory)
    }

    private fun createMediaFile(context: Context, chooseMode: Int, fileName: String?, format: String?, outCameraDirectory: String?): File {
        return createOutFile(context, chooseMode, fileName, format, outCameraDirectory)
    }

    private fun createOutFile(ctx: Context, chooseMode: Int, fileName: String?, format: String?, outCameraDirectory: String?): File {
        val context = ctx.applicationContext
        val folderDir: File
        if (TextUtils.isEmpty(outCameraDirectory)) {
            val rootDir: File
            if (TextUtils.equals(Environment.MEDIA_MOUNTED, Environment.getExternalStorageState())) {
                rootDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                folderDir = File(rootDir.absolutePath + File.separator + PictureMimeType.CAMERA + File.separator)
            } else {
                rootDir = getRootDirFile(context, chooseMode)
                folderDir = File(rootDir.absolutePath + File.separator)
            }
            if (!rootDir.exists()) {
                rootDir.mkdirs()
            }
        } else {
            folderDir = File(outCameraDirectory!!)
            if (!folderDir.parentFile!!.exists()) {
                folderDir.parentFile!!.mkdirs()
            }
        }
        if (!folderDir.exists()) {
            folderDir.mkdirs()
        }

        val isOutFileNameEmpty = TextUtils.isEmpty(fileName)
        return when (chooseMode) {
            SelectMimeType.TYPE_VIDEO -> {
                val newFileVideoName = if (isOutFileNameEmpty) DateUtils.getCreateFileName("VID_") + POSTFIX_MP4 else fileName
                File(folderDir, newFileVideoName!!)
            }
            SelectMimeType.TYPE_AUDIO -> {
                val newFileAudioName = if (isOutFileNameEmpty) DateUtils.getCreateFileName("AUD_") + POSTFIX_AMR else fileName
                File(folderDir, newFileAudioName!!)
            }
            else -> {
                val suffix = if (TextUtils.isEmpty(format)) POSTFIX_JPG else format
                val newFileImageName = if (isOutFileNameEmpty) DateUtils.getCreateFileName("IMG_") + suffix else fileName
                File(folderDir, newFileImageName!!)
            }
        }
    }

    private fun getRootDirFile(context: Context, type: Int): File {
        val fileDirPath = FileDirMap.getFileDirPath(context, type)
        return File(fileDirPath!!)
    }

    


    @JvmStatic
    fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    


    @JvmStatic
    fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    


    @JvmStatic
    fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    


    @JvmStatic
    fun isGooglePhotosUri(uri: Uri): Boolean {
        return "com.google.android.apps.photos.content" == uri.authority
    }

    





    @JvmStatic
    fun getDataColumn(context: Context, uri: Uri?, selection: String?, selectionArgs: Array<String>?): String {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)
        try {
            cursor = context.contentResolver.query(uri!!, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } catch (ex: IllegalArgumentException) {
            L.i { "[PictureFileUtils] getDataColumn: _data - $ex" }
        } finally {
            cursor?.close()
        }
        return ""
    }

    




    @SuppressLint("NewApi")
    @JvmStatic
    fun getPath(ctx: Context, uri: Uri): String? {
        val context = ctx.applicationContext

        
        if (DocumentsContract.isDocumentUri(context, uri)) {
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                val type = split[0]

                if ("primary".equals(type, ignoreCase = true)) {
                    return if (SdkVersionUtils.isQ()) {
                        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES).toString() + "/" + split[1]
                    } else {
                        Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                    }
                }
            } else if (isDownloadsDocument(uri)) {
                
                val id = DocumentsContract.getDocumentId(uri)
                val contentUri = ContentUris.withAppendedId(
                    Uri.parse("content://downloads/public_downloads"), ValueOf.toLong(id)
                )

                return getDataColumn(context, contentUri, null, null)
            } else if (isMediaDocument(uri)) {
                
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                val type = split[0]

                var contentUri: Uri? = null
                if ("image" == type) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])

                return getDataColumn(context, contentUri, selection, selectionArgs)
            }
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {
            
            
            if (isGooglePhotosUri(uri)) {
                return uri.lastPathSegment
            }
            return getDataColumn(context, uri, null, null)
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            
            return uri.path
        }

        return ""
    }

    





    @JvmStatic
    fun writeFileFromIS(`is`: InputStream?, os: OutputStream?): Boolean {
        var osBuffer: OutputStream? = null
        var isBuffer: BufferedInputStream? = null
        return try {
            isBuffer = BufferedInputStream(`is`)
            osBuffer = BufferedOutputStream(os)
            val data = ByteArray(BYTE_SIZE)
            var len: Int
            while (isBuffer.read(data).also { len = it } != -1) {
                os!!.write(data, 0, len)
            }
            os!!.flush()
            true
        } catch (e: Exception) {
            L.w(e) { "[PictureFileUtils] writeFileFromIS error:" }
            false
        } finally {
            close(isBuffer)
            close(osBuffer)
        }
    }

    @JvmStatic
    fun parUri(context: Context, cameraFile: File): Uri {
        val authority = context.packageName + ".luckProvider"
        return FileProvider.getUriForFile(context, authority, cameraFile)
    }

    @JvmStatic
    fun isImageFileExists(path: String): Boolean {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        options.inSampleSize = 2
        BitmapFactory.decodeFile(path, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    @JvmStatic
    fun isFileExists(path: String?): Boolean {
        return !TextUtils.isEmpty(path) && File(path!!).exists()
    }

    





    @SuppressLint("DefaultLocale")
    @JvmStatic
    fun formatFileSize(byteSize: Long): String {
        return when {
            byteSize < 0 -> throw IllegalArgumentException("byteSize shouldn't be less than zero!")
            byteSize < FileSizeUnit.KB -> {
                val format = String.format("%.2f", byteSize.toDouble())
                val num = ValueOf.toDouble(format)
                val round = Math.round(num)
                (if (round - num == 0.0) round else format).toString() + "B"
            }
            byteSize < FileSizeUnit.MB -> {
                val format = String.format("%.2f", byteSize.toDouble() / FileSizeUnit.KB)
                val num = ValueOf.toDouble(format)
                val round = Math.round(num)
                (if (round - num == 0.0) round else format).toString() + "KB"
            }
            byteSize < FileSizeUnit.GB -> {
                val format = String.format("%.2f", byteSize.toDouble() / FileSizeUnit.MB)
                val num = ValueOf.toDouble(format)
                val round = Math.round(num)
                (if (round - num == 0.0) round else format).toString() + "MB"
            }
            else -> {
                val format = String.format("%.2f", byteSize.toDouble() / FileSizeUnit.GB)
                val num = ValueOf.toDouble(format)
                val round = Math.round(num)
                (if (round - num == 0.0) round else format).toString() + "GB"
            }
        }
    }

    





    @SuppressLint("DefaultLocale")
    @JvmStatic
    fun formatAccurateUnitFileSize(byteSize: Long): String {
        var unit = ""
        val newByteSize: Double
        if (byteSize < 0) {
            throw IllegalArgumentException("byteSize shouldn't be less than zero!")
        } else if (byteSize < FileSizeUnit.ACCURATE_KB) {
            newByteSize = byteSize.toDouble()
        } else if (byteSize < FileSizeUnit.ACCURATE_MB) {
            unit = "KB"
            newByteSize = byteSize.toDouble() / FileSizeUnit.ACCURATE_KB
        } else if (byteSize < FileSizeUnit.ACCURATE_GB) {
            unit = "MB"
            newByteSize = byteSize.toDouble() / FileSizeUnit.ACCURATE_MB
        } else {
            unit = "GB"
            newByteSize = byteSize.toDouble() / FileSizeUnit.ACCURATE_GB
        }
        val format = String.format(Locale("zh"), "%.2f", newByteSize)
        return (if (Math.round(ValueOf.toDouble(format)) - ValueOf.toDouble(format) == 0.0) Math.round(ValueOf.toDouble(format)) else format).toString() + unit
    }

    @JvmStatic
    fun close(c: Closeable?) {
        
        if (c is Closeable) {
            try {
                c.close()
            } catch (e: Exception) {
                
                L.w(e) { "[PictureFileUtils] close failed" }
            }
        }
    }
}
