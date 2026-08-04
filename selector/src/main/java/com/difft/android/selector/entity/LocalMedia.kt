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

/**
 * Every constructor property is parceled (mirrors the fields the original writeToParcel wrote).
 * The three private *Flag properties carry the raw booleans that back the computed [isCut] /
 * [isOriginal] / [isEditorImage] states, so the parcel round-trip stays value-identical to the
 * original (which parceled the raw flag, not the path-gated computed value). Two of the original's
 * fields are absent from this parcel set: [compareLocalMedia] is kept as a class-body property so
 * @Parcelize excludes it (matching the original writeToParcel), and the original's
 * videoThumbnailPath was deleted outright with the video-thumbnail feature (zero callers) — it is
 * neither a property nor parceled here.
 */
@Parcelize
// EqualsOrHashCode: identity hashCode is deliberate — the OR-based equals admits no
// contract-consistent hashCode, and identity-keyed Map<LocalMedia, …> sites depend on it (see equals KDoc).
@Suppress("LongParameterList", "EqualsOrHashCode")
class LocalMedia(
    var id: Long = 0,
    /** original path (always populated by the loaders / factory before external reads) */
    var path: String = "",
    /** real path; not accessible from AndroidQ but always populated before external reads */
    var realPath: String = "",
    /** original path — only set when the "original" toggle is checked, else absent */
    var originalPath: String? = null,
    /** compress path — absent until a compress engine runs */
    var compressPath: String? = null,
    /** cut path — absent until a crop engine runs */
    var cutPath: String? = null,
    /**
     * watermark path — never assigned since the watermark feature was removed (no writers remain);
     * retained read-only because [isWatermarkPath] / [availablePath] still reference it.
     */
    var watermarkPath: String? = null,
    /** app sandbox path — absent unless copied into the sandbox */
    var sandboxPath: String? = null,
    var duration: Long = 0,
    /** whether the item is selected (internal use) */
    var isChecked: Boolean = false,
    /** raw cut flag; effective state is [isCut] (adds a non-empty [cutPath] guard) */
    private var cutFlag: Boolean = false,
    /** media position in the list */
    var position: Int = 0,
    /** the media number of qq choose styles */
    var num: Int = 0,
    /** media resource type — coalesced to a concrete type by the loaders, never absent */
    var mimeType: String = "",
    /** gallery selection mode */
    var chooseModel: Int = 0,
    /** camera-generated data source, only for taking photos once */
    var isCameraSource: Boolean = false,
    /** raw compressed flag; effective state is [isCompressed] (adds a non-empty [compressPath] guard) */
    var compressed: Boolean = false,
    var width: Int = 0,
    var height: Int = 0,
    var cropImageWidth: Int = 0,
    var cropImageHeight: Int = 0,
    var cropOffsetX: Int = 0,
    var cropOffsetY: Int = 0,
    var cropResultAspectRatio: Float = 0f,
    var size: Long = 0,
    /** raw original flag; effective state is [isOriginal] (adds a non-empty [originalPath] guard) */
    private var originalFlag: Boolean = false,
    /** file name — coalesced to a derived name by the loaders, never absent */
    var fileName: String = "",
    /** parent folder name — coalesced by the loaders, never absent */
    var parentFolderName: String = "",
    var bucketId: Long = PictureConfig.ALL.toLong(),
    /** media create time */
    var dateAddedTime: Long = 0,
    /** custom data — user-defined, absent by default */
    var customData: String? = null,
    /** isMaxSelectEnabledMask (internal use only) */
    var isMaxSelectEnabledMask: Boolean = false,
    /** isGalleryEnabledMask (internal use only) */
    var isGalleryEnabledMask: Boolean = false,
    /** raw editor-image flag; effective state is [isEditorImage] (adds a non-empty [cutPath] guard) */
    private var editorImageFlag: Boolean = false,
) : Parcelable {

    /** The object matched by the last [equals] comparison (internal use); never parceled. */
    @IgnoredOnParcel
    var compareLocalMedia: LocalMedia? = null

    /** Effective "is cut" state: raw flag AND a non-empty cut path. */
    var isCut: Boolean
        get() = cutFlag && !cutPath.isNullOrEmpty()
        set(value) {
            cutFlag = value
        }

    /** Effective "is compressed" state: raw flag AND a non-empty compress path. */
    val isCompressed: Boolean
        get() = compressed && !compressPath.isNullOrEmpty()

    /** Effective "is original" state: raw flag AND a non-empty original path. */
    var isOriginal: Boolean
        get() = originalFlag && !originalPath.isNullOrEmpty()
        set(value) {
            originalFlag = value
        }

    /** Effective "is editor image" state: raw flag AND a non-empty cut path. */
    var isEditorImage: Boolean
        get() = editorImageFlag && !cutPath.isNullOrEmpty()
        set(value) {
            editorImageFlag = value
        }

    val isToSandboxPath: Boolean
        get() = !sandboxPath.isNullOrEmpty()

    val isWatermarkPath: Boolean
        get() = !watermarkPath.isNullOrEmpty()

    /** Real, effective resource path, applying cut / compress / sandbox / original / watermark. */
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

    /**
     * Value-based equality matching on path OR realPath OR id. Deliberately does NOT override
     * hashCode (identity hashCode preserved from upstream), for two reasons:
     *  1. This OR-based equals admits no contract-consistent hashCode — equal instances (e.g. same
     *     id, different path) could not be forced to share a hash over {path, realPath, id}.
     *  2. LocalMedia is an identity-keyed map key (MediaSelectionRepository / LocalMediaExtensions);
     *     a value-based hashCode would merge distinct selections that compare equal.
     */
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
