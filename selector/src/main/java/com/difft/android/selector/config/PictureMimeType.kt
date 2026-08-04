package com.difft.android.selector.config

import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import java.util.Locale

object PictureMimeType {

    @JvmStatic
    fun isHasGif(mimeType: String?): Boolean {
        return mimeType != null && (mimeType == "image/gif" || mimeType == "image/GIF")
    }

    @JvmStatic
    fun isUrlHasGif(url: String): Boolean {
        return url.lowercase(Locale.ROOT).endsWith(".gif")
    }

    @JvmStatic
    fun isUrlHasImage(url: String): Boolean {
        return url.lowercase(Locale.ROOT).endsWith(".jpg")
                || url.lowercase(Locale.ROOT).endsWith(".jpeg")
                || url.lowercase(Locale.ROOT).endsWith(".png")
                || url.lowercase(Locale.ROOT).endsWith(".heic")
    }

    @JvmStatic
    fun isHasWebp(mimeType: String?): Boolean {
        return mimeType != null && mimeType.equals("image/webp", ignoreCase = true)
    }

    @JvmStatic
    fun isHasVideo(mimeType: String?): Boolean {
        return mimeType != null && mimeType.startsWith(MIME_TYPE_PREFIX_VIDEO)
    }

    @JvmStatic
    fun isUrlHasVideo(url: String): Boolean {
        return url.lowercase(Locale.ROOT).endsWith(".mp4")
    }

    @JvmStatic
    fun isHasAudio(mimeType: String?): Boolean {
        return mimeType != null && mimeType.startsWith(MIME_TYPE_PREFIX_AUDIO)
    }

    @JvmStatic
    fun isUrlHasAudio(url: String): Boolean {
        return url.lowercase(Locale.ROOT).endsWith(".amr") || url.lowercase(Locale.ROOT).endsWith(".mp3")
    }

    @JvmStatic
    fun isHasImage(mimeType: String?): Boolean {
        return mimeType != null && mimeType.startsWith(MIME_TYPE_PREFIX_IMAGE)
    }

    @JvmStatic
    fun isHasBmp(mimeType: String?): Boolean {
        if (TextUtils.isEmpty(mimeType)) {
            return false
        }
        return mimeType!!.startsWith(ofBMP())
                || mimeType.startsWith(ofXmsBMP())
                || mimeType.startsWith(ofWapBMP())
    }

    @JvmStatic
    fun isHasHeic(mimeType: String?): Boolean {
        if (TextUtils.isEmpty(mimeType)) {
            return false
        }
        return mimeType!!.startsWith(ofHeic())
    }

    /** Is it a network image. */
    @JvmStatic
    fun isHasHttp(path: String?): Boolean {
        if (TextUtils.isEmpty(path)) {
            return false
        }
        return path!!.startsWith("http") || path.startsWith("https")
    }

    /** Whether the two mime types are of the same media category. */
    @JvmStatic
    fun isMimeTypeSame(oldMimeType: String?, newMimeType: String?): Boolean {
        if (TextUtils.isEmpty(oldMimeType)) {
            return true
        }
        return getMimeType(oldMimeType) == getMimeType(newMimeType)
    }

    /** Picture, video or audio. */
    @JvmStatic
    fun getMimeType(mimeType: String?): Int {
        if (TextUtils.isEmpty(mimeType)) {
            return SelectMimeType.TYPE_IMAGE
        }
        return if (mimeType!!.startsWith(MIME_TYPE_PREFIX_VIDEO)) {
            SelectMimeType.TYPE_VIDEO
        } else if (mimeType.startsWith(MIME_TYPE_PREFIX_AUDIO)) {
            SelectMimeType.TYPE_AUDIO
        } else {
            SelectMimeType.TYPE_IMAGE
        }
    }

    @JvmStatic
    fun getLastSourceSuffix(mineType: String?): String {
        return try {
            mineType!!.substring(mineType.lastIndexOf("/")).replace("/", ".")
        } catch (e: Exception) {
            L.w(e) { "[PictureMimeType] getLastSourceSuffix error:" }
            JPG
        }
    }

    @JvmStatic
    fun getUrlToFileName(path: String?): String {
        var result = ""
        try {
            val lastIndexOf = path!!.lastIndexOf("/")
            if (lastIndexOf != -1) {
                result = path.substring(lastIndexOf + 1)
            }
        } catch (e: Exception) {
            L.w(e) { "[PictureMimeType] getUrlToFileName error:" }
        }
        return result
    }

    @JvmStatic
    fun isContent(url: String?): Boolean {
        if (TextUtils.isEmpty(url)) {
            return false
        }
        return url!!.startsWith("content://")
    }

    @JvmStatic
    fun ofJPEG(): String = MIME_TYPE_JPEG

    @JvmStatic
    fun ofBMP(): String = MIME_TYPE_BMP

    @JvmStatic
    fun ofXmsBMP(): String = MIME_TYPE_XMS_BMP

    @JvmStatic
    fun ofWapBMP(): String = MIME_TYPE_WAP_BMP

    @JvmStatic
    fun ofHeic(): String = MIME_TYPE_HEIC

    @JvmStatic
    fun ofGIF(): String = MIME_TYPE_GIF

    @JvmStatic
    fun ofWEBP(): String = MIME_TYPE_WEBP

    const val MIME_TYPE_IMAGE = "image/jpeg"
    const val MIME_TYPE_VIDEO = "video/mp4"
    const val MIME_TYPE_AUDIO = "audio/mpeg"

    const val MIME_TYPE_PREFIX_IMAGE = "image"
    const val MIME_TYPE_PREFIX_VIDEO = "video"
    const val MIME_TYPE_PREFIX_AUDIO = "audio"

    const val MIME_TYPE_JPEG = "image/jpeg"
    private const val MIME_TYPE_BMP = "image/bmp"
    private const val MIME_TYPE_XMS_BMP = "image/x-ms-bmp"
    private const val MIME_TYPE_WAP_BMP = "image/vnd.wap.wbmp"
    private const val MIME_TYPE_GIF = "image/gif"
    private const val MIME_TYPE_WEBP = "image/webp"
    private const val MIME_TYPE_HEIC = "image/heic"

    const val JPEG = ".jpeg"

    const val JPG = ".jpg"

    const val PNG = ".png"

    const val GIF = ".gif"

    const val AMR = ".amr"

    const val MP3 = ".mp3"

    const val MP4 = ".mp4"

    const val DCIM = "DCIM/Camera"

    const val CAMERA = "Camera"
}
