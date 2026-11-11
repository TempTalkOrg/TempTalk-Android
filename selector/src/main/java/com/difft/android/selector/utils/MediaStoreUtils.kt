package com.difft.android.selector.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectorConfig
import java.io.File

object MediaStoreUtils {

    @JvmStatic
    fun createCameraOutImageUri(context: Context, config: SelectorConfig): Uri? {
        val imageUri: Uri?
        val cameraFileName: String
        cameraFileName = if (TextUtils.isEmpty(config.outPutCameraImageFileName)) {
            ""
        } else {
            if (config.isOnlyCamera) config.outPutCameraImageFileName
            else System.currentTimeMillis().toString() + "_" + config.outPutCameraImageFileName
        }
        if (SdkVersionUtils.isQ() && TextUtils.isEmpty(config.outPutCameraDir)) {
            imageUri = createImageUri(context, cameraFileName, config.cameraImageFormatForQ)
            config.cameraPath = if (imageUri != null) imageUri.toString() else ""
        } else {
            val cameraFile = PictureFileUtils.createCameraFile(
                context, SelectMimeType.TYPE_IMAGE,
                cameraFileName, config.cameraImageFormat, config.outPutCameraDir
            )
            config.cameraPath = cameraFile.absolutePath
            imageUri = PictureFileUtils.parUri(context, cameraFile)
        }
        return imageUri
    }

    @JvmStatic
    fun createCameraOutVideoUri(context: Context, config: SelectorConfig): Uri? {
        val videoUri: Uri?
        val cameraFileName: String
        cameraFileName = if (TextUtils.isEmpty(config.outPutCameraVideoFileName)) {
            ""
        } else {
            if (config.isOnlyCamera) config.outPutCameraVideoFileName
            else System.currentTimeMillis().toString() + "_" + config.outPutCameraVideoFileName
        }
        if (SdkVersionUtils.isQ() && TextUtils.isEmpty(config.outPutCameraDir)) {
            videoUri = createVideoUri(context, cameraFileName, config.cameraVideoFormatForQ)
            config.cameraPath = if (videoUri != null) videoUri.toString() else ""
        } else {
            val cameraFile = PictureFileUtils.createCameraFile(
                context, SelectMimeType.TYPE_VIDEO,
                cameraFileName, config.cameraVideoFormat, config.outPutCameraDir
            )
            config.cameraPath = cameraFile.absolutePath
            videoUri = PictureFileUtils.parUri(context, cameraFile)
        }
        return videoUri
    }

    @JvmStatic
    fun createImageUri(ctx: Context, cameraFileName: String?, mimeType: String?): Uri? {
        val context = ctx.applicationContext
        val imageFilePath = arrayOfNulls<Uri>(1)
        val status = Environment.getExternalStorageState()
        val contentValues = buildImageContentValues(cameraFileName, mimeType)
        
        if (status == Environment.MEDIA_MOUNTED) {
            imageFilePath[0] = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            imageFilePath[0] = context.contentResolver
                .insert(MediaStore.Images.Media.INTERNAL_CONTENT_URI, contentValues)
        }
        return imageFilePath[0]
    }

    @JvmStatic
    fun buildImageContentValues(customFileName: String?, mimeType: String?): ContentValues {
        val time = ValueOf.toString(System.currentTimeMillis())
        val values = ContentValues(3)
        if (TextUtils.isEmpty(customFileName)) {
            values.put(MediaStore.Images.Media.DISPLAY_NAME, DateUtils.getCreateFileName("IMG_"))
        } else {
            if (customFileName!!.lastIndexOf(".") == -1) {
                values.put(MediaStore.Images.Media.DISPLAY_NAME, DateUtils.getCreateFileName("IMG_"))
            } else {
                val suffix = customFileName.substring(customFileName.lastIndexOf("."))
                val fileName = customFileName.replace(suffix.toRegex(), "")
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            }
        }
        values.put(
            MediaStore.Images.Media.MIME_TYPE,
            if (TextUtils.isEmpty(mimeType) || mimeType!!.startsWith(PictureMimeType.MIME_TYPE_PREFIX_VIDEO)) PictureMimeType.MIME_TYPE_IMAGE else mimeType
        )
        if (SdkVersionUtils.isQ()) {
            values.put(MediaStore.Images.Media.DATE_TAKEN, time)
            values.put(MediaStore.Images.Media.RELATIVE_PATH, PictureMimeType.DCIM)
        }
        return values
    }

    @JvmStatic
    fun createVideoUri(ctx: Context, cameraFileName: String?, mimeType: String?): Uri? {
        val context = ctx.applicationContext
        val imageFilePath = arrayOfNulls<Uri>(1)
        val status = Environment.getExternalStorageState()
        val contentValues = buildVideoContentValues(cameraFileName, mimeType)
        
        if (status == Environment.MEDIA_MOUNTED) {
            imageFilePath[0] = context.contentResolver
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            imageFilePath[0] = context.contentResolver
                .insert(MediaStore.Video.Media.INTERNAL_CONTENT_URI, contentValues)
        }
        return imageFilePath[0]
    }

    @JvmStatic
    fun buildVideoContentValues(customFileName: String?, mimeType: String?): ContentValues {
        val time = ValueOf.toString(System.currentTimeMillis())
        val values = ContentValues(3)
        if (TextUtils.isEmpty(customFileName)) {
            values.put(MediaStore.Video.Media.DISPLAY_NAME, DateUtils.getCreateFileName("VID_"))
        } else {
            if (customFileName!!.lastIndexOf(".") == -1) {
                values.put(MediaStore.Video.Media.DISPLAY_NAME, DateUtils.getCreateFileName("VID_"))
            } else {
                val suffix = customFileName.substring(customFileName.lastIndexOf("."))
                val fileName = customFileName.replace(suffix.toRegex(), "")
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            }
        }
        values.put(
            MediaStore.Video.Media.MIME_TYPE,
            if (TextUtils.isEmpty(mimeType) || mimeType!!.startsWith(PictureMimeType.MIME_TYPE_PREFIX_IMAGE)) PictureMimeType.MIME_TYPE_VIDEO else mimeType
        )
        if (SdkVersionUtils.isQ()) {
            values.put(MediaStore.Video.Media.DATE_TAKEN, time)
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
        }
        return values
    }
}
