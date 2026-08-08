package com.difft.android.selector.utils

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.webkit.MimeTypeMap
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.app.PictureAppMaster
import com.difft.android.selector.basic.PictureContentResolver
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.entity.MediaExtraInfo
import com.difft.android.selector.interfaces.OnCallbackListener
import com.difft.android.selector.thread.PictureThreadUtils
import com.difft.android.selector.thread.PictureThreadUtils.SimpleTask
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLConnection
import java.util.Locale

/**
 * 资源处理工具类
 */
object MediaUtils {

    /**
     * get uri
     */
    @JvmStatic
    fun getRealPathUri(id: Long, mimeType: String?): String {
        val contentUri = when {
            PictureMimeType.isHasImage(mimeType) -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            PictureMimeType.isHasVideo(mimeType) -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            PictureMimeType.isHasAudio(mimeType) -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        return ContentUris.withAppendedId(contentUri, id).toString()
    }

    /**
     * 获取mimeType
     */
    @JvmStatic
    fun getMimeTypeFromMediaUrl(path: String): String {
        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(path)
        var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            fileExtension.lowercase(Locale.ROOT)
        )
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = getMimeType(File(path))
        }
        return if (TextUtils.isEmpty(mimeType)) PictureMimeType.MIME_TYPE_JPEG else mimeType!!
    }

    /**
     * 获取mimeType
     */
    @JvmStatic
    fun getMimeTypeFromMediaHttpUrl(url: String?): String? {
        if (TextUtils.isEmpty(url)) {
            return null
        }
        val lower = url!!.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".bmp") -> "image/bmp"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".avi") -> "video/avi"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".amr") -> "audio/amr"
            lower.endsWith(".m4a") -> "audio/mpeg"
            else -> null
        }
    }

    /**
     * 获取mimeType
     */
    private fun getMimeType(file: File): String? {
        val fileNameMap = URLConnection.getFileNameMap()
        return fileNameMap.getContentTypeFor(file.name)
    }

    /**
     * 是否是长图
     */
    @JvmStatic
    fun isLongImage(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) {
            return false
        }
        return height > width * 3
    }

    /**
     * 创建目录名
     */
    @JvmStatic
    fun generateCameraFolderName(absolutePath: String): String {
        val cameraFile = File(absolutePath)
        return cameraFile.parentFile?.name ?: PictureMimeType.CAMERA
    }

    /**
     * get Local image width or height
     */
    @JvmStatic
    fun getImageSize(context: Context, url: String): MediaExtraInfo {
        val mediaExtraInfo = MediaExtraInfo()
        if (PictureMimeType.isHasHttp(url)) {
            return mediaExtraInfo
        }
        var inputStream: InputStream? = null
        try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            inputStream = if (PictureMimeType.isContent(url)) {
                PictureContentResolver.openInputStream(context, Uri.parse(url))
            } else {
                FileInputStream(url)
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            mediaExtraInfo.width = options.outWidth
            mediaExtraInfo.height = options.outHeight
        } catch (e: Exception) {
            L.w(e) { "[MediaUtils] getImageSize error:" }
        } finally {
            PictureFileUtils.close(inputStream)
        }
        return mediaExtraInfo
    }

    /**
     * get Local image width or height
     */
    @JvmStatic
    fun getImageSize(context: Context, url: String, call: OnCallbackListener<MediaExtraInfo>?) {
        PictureThreadUtils.executeByIo(object : SimpleTask<MediaExtraInfo>() {
            override fun doInBackground(): MediaExtraInfo = getImageSize(context, url)

            override fun onSuccess(result: MediaExtraInfo) {
                PictureThreadUtils.cancel(this)
                call?.onCall(result)
            }
        })
    }

    /**
     * get Local video width or height
     */
    @JvmStatic
    fun getVideoSize(context: Context, url: String, call: OnCallbackListener<MediaExtraInfo>?) {
        PictureThreadUtils.executeByIo(object : SimpleTask<MediaExtraInfo>() {
            override fun doInBackground(): MediaExtraInfo = getVideoSize(context, url)

            override fun onSuccess(result: MediaExtraInfo) {
                PictureThreadUtils.cancel(this)
                call?.onCall(result)
            }
        })
    }

    /**
     * get Local video width or height
     */
    @JvmStatic
    fun getVideoSize(context: Context, url: String): MediaExtraInfo {
        val mediaExtraInfo = MediaExtraInfo()
        if (PictureMimeType.isHasHttp(url)) {
            return mediaExtraInfo
        }
        val retriever = MediaMetadataRetriever()
        try {
            if (PictureMimeType.isContent(url)) {
                retriever.setDataSource(context, Uri.parse(url))
            } else {
                retriever.setDataSource(url)
            }
            val orientation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val width: Int
            val height: Int
            if (TextUtils.equals("90", orientation) || TextUtils.equals("270", orientation)) {
                height = ValueOf.toInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                width = ValueOf.toInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            } else {
                width = ValueOf.toInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                height = ValueOf.toInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            }
            mediaExtraInfo.width = width
            mediaExtraInfo.height = height
            mediaExtraInfo.orientation = orientation
            mediaExtraInfo.duration = ValueOf.toLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
        } catch (e: Exception) {
            L.w(e) { "[MediaUtils] getVideoSize error:" }
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                L.w(e) { "[MediaUtils] getVideoSize release retriever error:" }
            }
        }
        return mediaExtraInfo
    }

    /**
     * get Local audio duration
     */
    @JvmStatic
    fun getAudioSize(context: Context, url: String): MediaExtraInfo {
        val mediaExtraInfo = MediaExtraInfo()
        if (PictureMimeType.isHasHttp(url)) {
            return mediaExtraInfo
        }
        val retriever = MediaMetadataRetriever()
        try {
            if (PictureMimeType.isContent(url)) {
                retriever.setDataSource(context, Uri.parse(url))
            } else {
                retriever.setDataSource(url)
            }
            mediaExtraInfo.duration = ValueOf.toLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
        } catch (e: Exception) {
            L.w(e) { "[MediaUtils] getAudioSize error:" }
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                L.w(e) { "[MediaUtils] getAudioSize release retriever error:" }
            }
        }
        return mediaExtraInfo
    }

    /**
     * 删除部分手机 拍照在DCIM也生成一张的问题
     */
    @JvmStatic
    fun removeMedia(context: Context, id: Int) {
        try {
            val cr = context.applicationContext.contentResolver
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val selection = MediaStore.Images.Media._ID + "=?"
            cr.delete(uri, selection, arrayOf(id.toString()))
        } catch (e: Exception) {
            L.w(e) { "[MediaUtils] removeMedia error:" }
        }
    }

    /**
     * 获取DCIM文件下最新一条拍照记录
     */
    @JvmStatic
    fun getDCIMLastImageId(context: Context, absoluteDir: String): Int {
        var data: Cursor? = null
        return try {
            //selection: 指定查询条件
            val selection = MediaStore.Images.Media.DATA + " like ?"
            //定义selectionArgs：
            val selectionArgs = arrayOf("%$absoluteDir%")
            data = if (SdkVersionUtils.isR()) {
                val queryArgs = createQueryArgsBundle(selection, selectionArgs, 1, 0, MediaStore.Files.FileColumns._ID + " DESC")
                context.applicationContext.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, queryArgs, null)
            } else {
                val orderBy = MediaStore.Files.FileColumns._ID + " DESC limit 1 offset 0"
                context.applicationContext.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, selection, selectionArgs, orderBy)
            }
            if (data != null && data.count > 0 && data.moveToFirst()) {
                val id = data.getInt(data.getColumnIndex(MediaStore.Images.Media._ID))
                val date = data.getLong(data.getColumnIndex(MediaStore.Images.Media.DATE_ADDED))
                val duration = DateUtils.dateDiffer(date)
                // 最近时间1s以内的图片，可以判定是最新生成的重复照片
                if (duration <= 1) id else -1
            } else {
                -1
            }
        } catch (e: Exception) {
            L.w(e) { "[MediaUtils] getDCIMLastImageId error:" }
            -1
        } finally {
            data?.close()
        }
    }

    /**
     * getPathMediaBucketId
     */
    @JvmStatic
    fun getPathMediaBucketId(context: Context, absolutePath: String): Array<Long> {
        val mediaBucketId = arrayOf(0L, 0L)
        var data: Cursor? = null
        try {
            //selection: 指定查询条件
            val selection = MediaStore.Files.FileColumns.DATA + " like ?"
            //定义selectionArgs：
            val selectionArgs = arrayOf("%$absolutePath%")
            data = if (SdkVersionUtils.isR()) {
                val queryArgs = createQueryArgsBundle(selection, selectionArgs, 1, 0, MediaStore.Files.FileColumns._ID + " DESC")
                context.contentResolver.query(MediaStore.Files.getContentUri("external"), null, queryArgs, null)
            } else {
                val orderBy = MediaStore.Files.FileColumns._ID + " DESC limit 1 offset 0"
                context.contentResolver.query(MediaStore.Files.getContentUri("external"), null, selection, selectionArgs, orderBy)
            }
            if (data != null && data.count > 0 && data.moveToFirst()) {
                mediaBucketId[0] = data.getLong(data.getColumnIndex(MediaStore.Files.FileColumns._ID))
                mediaBucketId[1] = data.getLong(data.getColumnIndex("bucket_id"))
            }
        } catch (e: Exception) {
            L.w(e) { "[MediaUtils] getPathMediaBucketId error:" }
        } finally {
            data?.close()
        }
        return mediaBucketId
    }

    /**
     * R  createQueryArgsBundle
     */
    @JvmStatic
    fun createQueryArgsBundle(selection: String, selectionArgs: Array<String>, limitCount: Int, offset: Int, orderBy: String): Bundle {
        val queryArgs = Bundle()
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, orderBy)
        if (SdkVersionUtils.isR()) {
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_LIMIT, "$limitCount offset $offset")
        }
        return queryArgs
    }

    /**
     * delete camera PATH
     */
    @JvmStatic
    fun deleteUri(context: Context, path: String?) {
        try {
            if (!TextUtils.isEmpty(path) && PictureMimeType.isContent(path)) {
                context.contentResolver.delete(Uri.parse(path), null, null)
            }
        } catch (e: Exception) {
            L.w(e) { "[MediaUtils] deleteUri error:" }
        }
    }
}
