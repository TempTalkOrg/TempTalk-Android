package com.difft.android.selector.entity

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.text.TextUtils
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.obj.pool.ObjectPools
import com.difft.android.selector.utils.MediaUtils
import com.difft.android.selector.utils.PictureFileUtils
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.io.File











@Parcelize


@Suppress("LongParameterList", "EqualsOrHashCode")
class LocalMedia(
    var id: Long = 0,
    
    var path: String = "",
    
    var realPath: String = "",
    
    var originalPath: String? = null,
    
    var compressPath: String? = null,
    
    var cutPath: String? = null,
    



    var watermarkPath: String? = null,
    
    var sandboxPath: String? = null,
    var duration: Long = 0,
    
    var isChecked: Boolean = false,
    
    private var cutFlag: Boolean = false,
    
    var position: Int = 0,
    
    var num: Int = 0,
    
    var mimeType: String = "",
    
    var chooseModel: Int = 0,
    
    var isCameraSource: Boolean = false,
    
    var compressed: Boolean = false,
    var width: Int = 0,
    var height: Int = 0,
    var cropImageWidth: Int = 0,
    var cropImageHeight: Int = 0,
    var cropOffsetX: Int = 0,
    var cropOffsetY: Int = 0,
    var cropResultAspectRatio: Float = 0f,
    var size: Long = 0,
    
    private var originalFlag: Boolean = false,
    
    var fileName: String = "",
    
    var parentFolderName: String = "",
    var bucketId: Long = PictureConfig.ALL.toLong(),
    
    var dateAddedTime: Long = 0,
    
    var customData: String? = null,
    
    var isMaxSelectEnabledMask: Boolean = false,
    
    var isGalleryEnabledMask: Boolean = false,
    
    private var editorImageFlag: Boolean = false,
) : Parcelable {

    
    @IgnoredOnParcel
    var compareLocalMedia: LocalMedia? = null

    
    fun copyForTransform(): LocalMedia = LocalMedia(
        id = id,
        path = path,
        realPath = realPath,
        originalPath = originalPath,
        compressPath = compressPath,
        cutPath = cutPath,
        watermarkPath = watermarkPath,
        sandboxPath = sandboxPath,
        duration = duration,
        isChecked = isChecked,
        cutFlag = cutFlag,
        position = position,
        num = num,
        mimeType = mimeType,
        chooseModel = chooseModel,
        isCameraSource = isCameraSource,
        compressed = compressed,
        width = width,
        height = height,
        cropImageWidth = cropImageWidth,
        cropImageHeight = cropImageHeight,
        cropOffsetX = cropOffsetX,
        cropOffsetY = cropOffsetY,
        cropResultAspectRatio = cropResultAspectRatio,
        size = size,
        originalFlag = originalFlag,
        fileName = fileName,
        parentFolderName = parentFolderName,
        bucketId = bucketId,
        dateAddedTime = dateAddedTime,
        customData = customData,
        isMaxSelectEnabledMask = isMaxSelectEnabledMask,
        isGalleryEnabledMask = isGalleryEnabledMask,
        editorImageFlag = editorImageFlag,
    ).also { it.compareLocalMedia = compareLocalMedia }

    
    var isCut: Boolean
        get() = cutFlag && !cutPath.isNullOrEmpty()
        set(value) {
            cutFlag = value
        }

    
    val isCompressed: Boolean
        get() = compressed && !compressPath.isNullOrEmpty()

    
    var isOriginal: Boolean
        get() = originalFlag && !originalPath.isNullOrEmpty()
        set(value) {
            originalFlag = value
        }

    
    var isEditorImage: Boolean
        get() = editorImageFlag && !cutPath.isNullOrEmpty()
        set(value) {
            editorImageFlag = value
        }

    val isToSandboxPath: Boolean
        get() = !sandboxPath.isNullOrEmpty()

    val isWatermarkPath: Boolean
        get() = !watermarkPath.isNullOrEmpty()

    
    val availablePath: String
        get() {
            var result = path
            if (isCut) result = cutPath ?: result
            if (isCompressed) result = compressPath ?: result
            if (isToSandboxPath) result = sandboxPath ?: result
            if (isOriginal) result = originalPath ?: result
            if (isWatermarkPath) result = watermarkPath ?: result
            return result
        }

    







    @Suppress("EqualsWithHashCodeExist")
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalMedia) return false
        val isCompare = TextUtils.equals(path, other.path) ||
            TextUtils.equals(realPath, other.realPath) ||
            id == other.id
        compareLocalMedia = if (isCompare) other else null
        return isCompare
    }

    fun recycle() {
        sPool?.release(this)
    }

    companion object {
        private var sPool: ObjectPools.SynchronizedPool<LocalMedia>? = null

        @JvmStatic
        fun generateLocalMedia(context: Context, path: String): LocalMedia {
            val media = create()
            val cameraFile = if (PictureMimeType.isContent(path)) {
                File(PictureFileUtils.getPath(context, Uri.parse(path))!!)
            } else {
                File(path)
            }
            media.path = path
            media.realPath = cameraFile.absolutePath
            media.fileName = cameraFile.name
            media.parentFolderName = MediaUtils.generateCameraFolderName(cameraFile.absolutePath)
            media.mimeType = MediaUtils.getMimeTypeFromMediaUrl(cameraFile.absolutePath)
            media.size = cameraFile.length()
            media.dateAddedTime = cameraFile.lastModified() / 1000
            val realPath = cameraFile.absolutePath
            if (realPath.contains("Android/data/") || realPath.contains("data/user/")) {
                media.id = System.currentTimeMillis()
                val parentFile = cameraFile.parentFile
                media.bucketId = if (parentFile != null) parentFile.name.hashCode().toLong() else 0L
            } else {
                val mediaBucketId = MediaUtils.getPathMediaBucketId(context, media.realPath)
                media.id = if (mediaBucketId[0] == 0L) System.currentTimeMillis() else mediaBucketId[0]
                media.bucketId = mediaBucketId[1]
            }
            val mediaExtraInfo: MediaExtraInfo
            if (PictureMimeType.isHasVideo(media.mimeType)) {
                mediaExtraInfo = MediaUtils.getVideoSize(context, path)
                media.width = mediaExtraInfo.width
                media.height = mediaExtraInfo.height
                media.duration = mediaExtraInfo.duration
            } else if (PictureMimeType.isHasAudio(media.mimeType)) {
                mediaExtraInfo = MediaUtils.getAudioSize(context, path)
                media.duration = mediaExtraInfo.duration
            } else {
                mediaExtraInfo = MediaUtils.getImageSize(context, path)
                media.width = mediaExtraInfo.width
                media.height = mediaExtraInfo.height
            }
            return media
        }

        @JvmStatic
        fun create(): LocalMedia = LocalMedia()

        @JvmStatic
        fun obtain(): LocalMedia {
            val pool = sPool ?: ObjectPools.SynchronizedPool<LocalMedia>().also { sPool = it }
            return pool.acquire() ?: create()
        }

        @JvmStatic
        fun destroyPool() {
            sPool?.destroy()
            sPool = null
        }
    }
}
