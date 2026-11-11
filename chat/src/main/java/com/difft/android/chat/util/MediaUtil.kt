/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.difft.android.chat.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.text.TextUtils
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.difft.android.base.log.lumberjack.L
import java.io.File
import difft.android.messageserialization.model.Attachment
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.chat.mms.PartAuthority
import java.io.IOException
import java.util.Locale

object MediaUtil {
    private val TAG: String = L.tag(MediaUtil::class.java)

    const val IMAGE_PNG: String = "image/png"
    const val IMAGE_JPEG: String = "image/jpeg"
    const val IMAGE_HEIC: String = "image/heic"
    const val IMAGE_HEIF: String = "image/heif"
    const val IMAGE_AVIF: String = "image/avif"
    const val IMAGE_WEBP: String = "image/webp"
    const val IMAGE_GIF: String = "image/gif"
    const val AUDIO_AAC: String = "audio/aac"
    const val AUDIO_MP4: String = "audio/mp4"
    const val AUDIO_UNSPECIFIED: String = "audio/*"
    const val VIDEO_MP4: String = "video/mp4"
    const val VIDEO_UNSPECIFIED: String = "video/*"
    const val VCARD: String = "text/x-vcard"
    const val LONG_TEXT: String = "text/x-signal-plain"
    const val VIEW_ONCE: String = "application/x-signal-view-once"
    const val UNKNOWN: String = "*/*"
    const val OCTET: String = "application/octet-stream"

    fun getMimeType(context: Context, uri: Uri?): String? {
        return getMimeType(context, uri, null)
    }

    fun getMimeType(context: Context, uri: Uri?, fileExtension: String?): String? {
        if (uri == null) return null

        var type = context.contentResolver.getType(uri)
        if (type == null || isOctetStream(type)) {
            var extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            if (TextUtils.isEmpty(extension) && fileExtension != null) {
                extension = fileExtension
            }
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase(Locale.getDefault()))
            return getCorrectedMimeType(type, fileExtension)
        }

        return getCorrectedMimeType(type)
    }

    fun getExtension(context: Context, uri: Uri?): String? {
        return MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(getMimeType(context, uri))
    }

    private fun safeMimeTypeOverride(originalType: String?, overrideType: String): String? {
        if (MimeTypeMap.getSingleton().hasMimeType(overrideType)) {
            return overrideType
        }
        return originalType
    }

    fun overrideMimeTypeWithExtension(mimeType: String?, fileExtension: String?): String? {
        if (fileExtension == null) {
            return mimeType
        }
        if (fileExtension.lowercase(Locale.getDefault()) == "m4a") {
            return safeMimeTypeOverride(mimeType, AUDIO_MP4)
        }
        return mimeType
    }

    @JvmStatic
    fun getCorrectedMimeType(mimeType: String?): String? {
        return getCorrectedMimeType(mimeType, null)
    }

    fun getCorrectedMimeType(mimeType: String?, fileExtension: String?): String? {
        if (mimeType == null) return null

        return when (mimeType) {
            "image/jpg" -> safeMimeTypeOverride(mimeType, IMAGE_JPEG)
            "audio/mpeg" -> overrideMimeTypeWithExtension(mimeType, fileExtension)
            else -> mimeType
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getMediaSize(context: Context?, uri: Uri?): Long {
        val `in` = PartAuthority.getAttachmentStream(context!!, uri!!) ?: throw IOException("Couldn't obtain input stream.")

        var size: Long = 0
        val buffer = ByteArray(4096)
        var read: Int

        while ((`in`.read(buffer).also { read = it }) != -1) {
            size += read.toLong()
        }
        `in`.close()

        return size
    }

    fun isMms(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim() == "application/mms"
    }

    @JvmStatic
    fun isGif(attachment: Attachment): Boolean {
        return isGif(attachment.contentType)
    }

    fun isJpeg(attachment: Attachment): Boolean {
        return isJpegType(attachment.contentType)
    }

    fun isHeic(attachment: Attachment): Boolean {
        return isHeicType(attachment.contentType)
    }

    fun isHeif(attachment: Attachment): Boolean {
        return isHeifType(attachment.contentType)
    }

    @JvmStatic
    fun isImage(attachment: Attachment): Boolean {
        return isImageType(attachment.contentType)
    }

    fun isAudio(attachment: Attachment): Boolean {
        return isAudioType(attachment.contentType)
    }

    @JvmStatic
    fun isVideo(attachment: Attachment): Boolean {
        return isVideoType(attachment.contentType)
    }

    fun isVideo(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim().startsWith("video/")
    }

    fun isVcard(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim() == VCARD
    }

    @JvmStatic
    fun isGif(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim() == "image/gif"
    }

    @JvmStatic
    fun isJpegType(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim() == IMAGE_JPEG
    }

    @JvmStatic
    fun isHeicType(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim() == IMAGE_HEIC
    }

    @JvmStatic
    fun isHeifType(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim() == IMAGE_HEIF
    }

    @JvmStatic
    fun isAvifType(contentType: String): Boolean {
        return !TextUtils.isEmpty(contentType) && contentType.trim() == IMAGE_AVIF
    }

    fun isFile(attachment: Attachment): Boolean {
        return !isGif(attachment) && !isImage(attachment) && !isAudio(attachment) && !isVideo(attachment)
    }

    fun isTextType(contentType: String?): Boolean {
        return (null != contentType) && contentType.startsWith("text/")
    }

    fun isNonGifVideo(media: LocalMedia): Boolean {
        return isVideo(media.mimeType)
    }

    @JvmStatic
    fun isImageType(contentType: String?): Boolean {
        if (contentType == null) {
            return false
        }

        return (contentType.startsWith("image/") && contentType != "image/svg+xml") || (contentType == MediaStore.Images.Media.CONTENT_TYPE)
    }

    fun isAudioType(contentType: String?): Boolean {
        if (contentType == null) {
            return false
        }

        return contentType.startsWith("audio/") || contentType == MediaStore.Audio.Media.CONTENT_TYPE
    }

    @JvmStatic
    fun isVideoType(contentType: String?): Boolean {
        if (contentType == null) {
            return false
        }

        return contentType.startsWith("video/") || contentType == MediaStore.Video.Media.CONTENT_TYPE
    }

    fun isImageOrVideoType(contentType: String?): Boolean {
        return isImageType(contentType) || isVideoType(contentType)
    }

    







    @androidx.annotation.StringRes
    fun quoteTypeLabelRes(contentType: String?, flags: Int): Int = when {
        contentType != null && isImageType(contentType) && difft.android.messageserialization.model.isAnimatedImage(flags, contentType) -> com.difft.android.chat.R.string.chat_message_gif
        isImageType(contentType) -> com.difft.android.chat.R.string.chat_message_image
        difft.android.messageserialization.model.isWhitelistedVideoType(contentType) -> com.difft.android.chat.R.string.chat_message_video
        isAudioType(contentType) -> com.difft.android.chat.R.string.chat_message_audio
        else -> com.difft.android.chat.R.string.chat_message_attachment
    }

    fun isStorySupportedType(contentType: String): Boolean {
        return isImageOrVideoType(contentType) && !isGif(contentType)
    }

    @JvmStatic
    fun isImageVideoOrAudioType(contentType: String?): Boolean {
        return isImageOrVideoType(contentType) || isAudioType(contentType)
    }

    fun isImageAndNotGif(contentType: String): Boolean {
        return isImageType(contentType) && !isGif(contentType)
    }

    fun isLongTextType(contentType: String?): Boolean {
        return (null != contentType) && (contentType == LONG_TEXT)
    }

    fun isViewOnceType(contentType: String?): Boolean {
        return (null != contentType) && (contentType == VIEW_ONCE)
    }

    @JvmStatic
    fun isOctetStream(contentType: String?): Boolean {
        return OCTET == contentType
    }

    





    fun getMediaWidthAndHeight(context: Context, uri: Uri, mimeType: String): Pair<Int, Int> {
        var width = 0
        var height = 0
        if (isImageType(mimeType)) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, options)
                    width = options.outWidth
                    height = options.outHeight
                }
            } catch (e: Exception) {
                L.w { "[MediaUtil] decode bounds from uri failed: ${e.stackTraceToString()}" }
            }
            
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val orientation = ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90,
                        ExifInterface.ORIENTATION_ROTATE_270,
                        ExifInterface.ORIENTATION_TRANSPOSE,
                        ExifInterface.ORIENTATION_TRANSVERSE -> {
                            val temp = width
                            width = height
                            height = temp
                        }
                    }
                }
            } catch (e: Exception) {
                L.w { "[MediaUtil] read EXIF from uri failed: ${e.message}" }
            }
        } else if (isVideoType(mimeType)) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                L.w { "[MediaUtil] video size from uri failed: ${e.message}" }
            } finally {
                mmr.release()
            }
        }
        return width to height
    }

    



    private fun Throwable.locationForLog(): String =
        "${javaClass.simpleName} at ${stackTrace.take(3).joinToString(" <- ")}"

    fun getMediaWidthAndHeight(filePath: String, mimeType: String): Pair<Int, Int> {
        var width = 0
        var height = 0
        if (isImageType(mimeType)) {
            val options: BitmapFactory.Options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(filePath, options)
            width = options.outWidth
            height = options.outHeight

            try {
                val exif = ExifInterface(filePath)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )

                
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        val temp = width
                        width = height
                        height = temp
                    }
                }
            } catch (e: Exception) {
                
                L.w { "[MediaUtil] exif orientation read failed ext=${File(filePath).extension} ${e.locationForLog()}" }
            }
        } else if (isVideoType(mimeType)) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(filePath)
                val bitmap = mmr.frameAtTime
                if (bitmap != null) {
                    width = bitmap.width
                    height = bitmap.height
                }
            } catch (e: Exception) {
                L.w { "[MediaUtil] video size read failed ext=${File(filePath).extension} exists=${File(filePath).exists()} ${e.locationForLog()}" }
            } finally {
                mmr.release()
            }
        }

        return width to height
    }

}
