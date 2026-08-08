package com.difft.android.selector.loader

import android.content.Context
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.entity.LocalMediaFolder
import com.difft.android.selector.utils.MediaUtils
import com.difft.android.selector.utils.SdkVersionUtils
import com.difft.android.selector.utils.SortUtils
import com.difft.android.selector.utils.ValueOf
import java.io.File
import java.io.FileFilter
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.ArrayList

object SandboxFileLoader {

    /**
     * Query images in the app's internal directory.
     */
    fun loadInAppSandboxFolderFile(context: Context, sandboxDir: String?): LocalMediaFolder? {
        val list = loadInAppSandboxFile(context, sandboxDir)
        var folder: LocalMediaFolder? = null
        if (list != null && list.size > 0) {
            SortUtils.sortLocalMediaAddedTime(list)
            val firstMedia = list[0]
            folder = LocalMediaFolder()
            folder.folderName = firstMedia.parentFolderName
            folder.firstImagePath = firstMedia.path
            folder.firstMimeType = firstMedia.mimeType
            folder.bucketId = firstMedia.bucketId
            folder.folderTotalNum = list.size
            folder.data = list
        }
        return folder
    }

    /**
     * Query images in the app's internal directory.
     *
     * 1:1 Java→Kotlin port (issue #1077); filter/MediaStore logic kept verbatim,
     * structural split deferred — see #1077 future work.
     */
    @Suppress("LongMethod")
    fun loadInAppSandboxFile(context: Context, sandboxDir: String?): ArrayList<LocalMedia>? {
        if (TextUtils.isEmpty(sandboxDir)) {
            return null
        }
        val list = ArrayList<LocalMedia>()
        val sandboxFile = File(sandboxDir!!)
        if (sandboxFile.exists()) {
            val files = sandboxFile.listFiles(FileFilter { file -> !file.isDirectory })
            if (files == null) {
                return list
            }
            val config = SelectorProviders.getInstance().selectorConfig
            var md: MessageDigest? = null
            try {
                md = MessageDigest.getInstance("MD5")
            } catch (e: NoSuchAlgorithmException) {
                L.w(e) { "[SandboxFileLoader] loadInAppSandboxFolderFile MD5 error:" }
            }
            for (f in files) {
                val mimeType = MediaUtils.getMimeTypeFromMediaUrl(f.absolutePath)
                if (config.chooseMode == SelectMimeType.ofImage()) {
                    if (!PictureMimeType.isHasImage(mimeType)) {
                        continue
                    }
                    if (config.queryOnlyImageList != null
                        && config.queryOnlyImageList.size > 0
                        && !config.queryOnlyImageList.contains(mimeType)
                    ) {
                        continue
                    }
                } else if (config.chooseMode == SelectMimeType.ofVideo()) {
                    if (!PictureMimeType.isHasVideo(mimeType)) {
                        continue
                    }
                    if (config.queryOnlyVideoList != null
                        && config.queryOnlyVideoList.size > 0
                        && !config.queryOnlyVideoList.contains(mimeType)
                    ) {
                        continue
                    }
                } else if (config.chooseMode == SelectMimeType.ofAudio()) {
                    if (!PictureMimeType.isHasAudio(mimeType)) {
                        continue
                    }
                    if (config.queryOnlyAudioList != null
                        && config.queryOnlyAudioList.size > 0
                        && !config.queryOnlyAudioList.contains(mimeType)
                    ) {
                        continue
                    }
                }

                if (!config.isGif) {
                    if (PictureMimeType.isHasGif(mimeType)) {
                        continue
                    }
                }
                val absolutePath = f.absolutePath
                val size = f.length()
                if (size <= 0) {
                    continue
                }
                val id: Long
                if (md != null) {
                    md.update(absolutePath.toByteArray())
                    id = BigInteger(1, md.digest()).toLong()
                } else {
                    id = f.lastModified() / 1000
                }
                val bucketId = ValueOf.toLong(sandboxFile.name.hashCode())
                val dateAdded = f.lastModified() / 1000
                val duration: Long
                val width: Int
                val height: Int
                if (PictureMimeType.isHasVideo(mimeType)) {
                    val videoSize = MediaUtils.getVideoSize(context, absolutePath)
                    width = videoSize.width
                    height = videoSize.height
                    duration = videoSize.duration
                } else if (PictureMimeType.isHasAudio(mimeType)) {
                    val audioSize = MediaUtils.getAudioSize(context, absolutePath)
                    width = audioSize.width
                    height = audioSize.height
                    duration = audioSize.duration
                } else {
                    val imageSize = MediaUtils.getImageSize(context, absolutePath)
                    width = imageSize.width
                    height = imageSize.height
                    duration = 0L
                }

                if (PictureMimeType.isHasVideo(mimeType) || PictureMimeType.isHasAudio(mimeType)) {
                    if (config.filterVideoMinSecond > 0 && duration < config.filterVideoMinSecond) {
                        // If you set the minimum number of seconds of video to display
                        continue
                    }
                    if (config.filterVideoMaxSecond > 0 && duration > config.filterVideoMaxSecond) {
                        // If you set the maximum number of seconds of video to display
                        continue
                    }
                    if (duration == 0L) {
                        // If the length is 0, the corrupted video is processed and filtered out
                        continue
                    }
                }
                val media = LocalMedia.create()
                media.id = id
                media.path = absolutePath
                media.realPath = absolutePath
                media.fileName = f.name
                media.parentFolderName = sandboxFile.name
                media.duration = duration
                media.chooseModel = config.chooseMode
                media.mimeType = mimeType
                media.width = width
                media.height = height
                media.size = size
                media.bucketId = bucketId
                media.dateAddedTime = dateAdded
                media.sandboxPath = if (SdkVersionUtils.isQ()) absolutePath else null
                list.add(media)
            }
        }
        return list
    }
}
