package com.difft.android.selector.loader

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.R
import com.difft.android.selector.config.FileSizeUnit
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.entity.LocalMediaFolder
import com.difft.android.selector.interfaces.OnQueryAlbumListener
import com.difft.android.selector.interfaces.OnQueryAllAlbumListener
import com.difft.android.selector.interfaces.OnQueryDataResultListener
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.MAX_SORT_SIZE
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.ORDER_BY
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.PROJECTION
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.QUERY_URI
import com.difft.android.selector.thread.PictureThreadUtils
import com.difft.android.selector.utils.MediaUtils
import com.difft.android.selector.utils.SdkVersionUtils
import com.difft.android.selector.utils.SortUtils
import java.util.ArrayList

/**
 * Local media database query class.
 */
class LocalMediaLoader(context: Context, config: SelectorConfig) : IBridgeMediaLoader(context, config) {

    override fun loadAllAlbum(query: OnQueryAllAlbumListener<LocalMediaFolder>?) {
        PictureThreadUtils.executeByIo(object : PictureThreadUtils.SimpleTask<List<LocalMediaFolder>>() {

            override fun doInBackground(): List<LocalMediaFolder> {
                val imageFolders: MutableList<LocalMediaFolder> = ArrayList()
                val data = getContext().contentResolver.query(
                    QUERY_URI, PROJECTION,
                    getSelection(), getSelectionArgs(), getSortOrder(),
                )
                try {
                    if (data != null) {
                        val allImageFolder = LocalMediaFolder()
                        val latelyImages = ArrayList<LocalMedia>()
                        val count = data.count
                        if (count > 0) {
                            data.moveToFirst()
                            do {
                                val media = parseLocalMedia(data, false) ?: continue
                                val folder = getImageFolder(
                                    media.path,
                                    media.mimeType, media.parentFolderName, imageFolders,
                                )
                                folder.bucketId = media.bucketId
                                folder.data!!.add(media)
                                folder.folderTotalNum = folder.folderTotalNum + 1
                                folder.bucketId = media.bucketId
                                latelyImages.add(media)
                                val imageNum = allImageFolder.folderTotalNum
                                allImageFolder.folderTotalNum = imageNum + 1
                            } while (data.moveToNext())

                            val selfFolder = SandboxFileLoader
                                .loadInAppSandboxFolderFile(getContext(), getConfig().sandboxDir)
                            if (selfFolder != null) {
                                imageFolders.add(selfFolder)
                                allImageFolder.folderTotalNum =
                                    allImageFolder.folderTotalNum + selfFolder.folderTotalNum
                                allImageFolder.data = selfFolder.data
                                latelyImages.addAll(0, selfFolder.data!!)
                                if (MAX_SORT_SIZE > selfFolder.folderTotalNum) {
                                    if (latelyImages.size > MAX_SORT_SIZE) {
                                        SortUtils.sortLocalMediaAddedTime(latelyImages.subList(0, MAX_SORT_SIZE))
                                    } else {
                                        SortUtils.sortLocalMediaAddedTime(latelyImages)
                                    }
                                }
                            }

                            if (latelyImages.size > 0) {
                                SortUtils.sortFolder(imageFolders)
                                imageFolders.add(0, allImageFolder)
                                allImageFolder.firstImagePath = latelyImages[0].path
                                allImageFolder.firstMimeType = latelyImages[0].mimeType
                                val folderName: String
                                if (TextUtils.isEmpty(getConfig().defaultAlbumName)) {
                                    folderName = if (getConfig().chooseMode == SelectMimeType.ofAudio()) {
                                        getContext().getString(R.string.ps_all_audio)
                                    } else {
                                        getContext().getString(R.string.ps_camera_roll)
                                    }
                                } else {
                                    folderName = getConfig().defaultAlbumName!!
                                }
                                allImageFolder.folderName = folderName
                                allImageFolder.bucketId = PictureConfig.ALL.toLong()
                                allImageFolder.data = latelyImages
                            }
                        }
                    }
                } catch (e: Exception) {
                    L.w(e) { "[LocalMediaLoader] loadAllAlbum error:" }
                } finally {
                    if (data != null && !data.isClosed) {
                        data.close()
                    }
                }
                return imageFolders
            }

            override fun onSuccess(result: List<LocalMediaFolder>) {
                PictureThreadUtils.cancel(this)
                query?.onComplete(result)
            }
        })
    }

    override fun loadOnlyInAppDirAllMedia(listener: OnQueryAlbumListener<LocalMediaFolder?>?) {
        PictureThreadUtils.executeByIo(object : PictureThreadUtils.SimpleTask<LocalMediaFolder?>() {

            override fun doInBackground(): LocalMediaFolder? {
                return SandboxFileLoader.loadInAppSandboxFolderFile(getContext(), getConfig().sandboxDir)
            }

            override fun onSuccess(result: LocalMediaFolder?) {
                PictureThreadUtils.cancel(this)
                listener?.onComplete(result)
            }
        })
    }

    override fun loadPageMediaData(
        bucketId: Long,
        page: Int,
        pageSize: Int,
        query: OnQueryDataResultListener<LocalMedia>?,
    ) {
    }

    override fun getAlbumFirstCover(bucketId: Long): String? {
        return null
    }

    override fun getSelection(): String? {
        val durationCondition = getDurationCondition()
        val fileSizeCondition = getFileSizeCondition()
        return when (getConfig().chooseMode) {
            SelectMimeType.TYPE_ALL ->
                // Get all, not including audio
                getSelectionArgsForAllMediaCondition(durationCondition, fileSizeCondition, getImageMimeTypeCondition(), getVideoMimeTypeCondition())

            SelectMimeType.TYPE_IMAGE ->
                // Gets the image
                getSelectionArgsForImageMediaCondition(fileSizeCondition, getImageMimeTypeCondition())

            SelectMimeType.TYPE_VIDEO ->
                // Access to video
                getSelectionArgsForVideoMediaCondition(durationCondition, getVideoMimeTypeCondition())

            SelectMimeType.TYPE_AUDIO ->
                // Access to the audio
                getSelectionArgsForAudioMediaCondition(durationCondition, getAudioMimeTypeCondition())

            else -> null
        }
    }

    override fun getSelectionArgs(): Array<String>? {
        return when (getConfig().chooseMode) {
            SelectMimeType.TYPE_ALL ->
                // Get all
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                )

            SelectMimeType.TYPE_IMAGE ->
                // Get photo
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())

            SelectMimeType.TYPE_VIDEO ->
                // Get video
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())

            SelectMimeType.TYPE_AUDIO ->
                // Get audio
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())

            else -> null
        }
    }

    override fun getSortOrder(): String {
        return if (TextUtils.isEmpty(getConfig().sortOrder)) ORDER_BY else getConfig().sortOrder!!
    }

    override fun parseLocalMedia(data: Cursor, isUsePool: Boolean): LocalMedia? {
        val idColumn = data.getColumnIndexOrThrow(PROJECTION[0])
        val dataColumn = data.getColumnIndexOrThrow(PROJECTION[1])
        val mimeTypeColumn = data.getColumnIndexOrThrow(PROJECTION[2])
        val widthColumn = data.getColumnIndexOrThrow(PROJECTION[3])
        val heightColumn = data.getColumnIndexOrThrow(PROJECTION[4])
        val durationColumn = data.getColumnIndexOrThrow(PROJECTION[5])
        val sizeColumn = data.getColumnIndexOrThrow(PROJECTION[6])
        val folderNameColumn = data.getColumnIndexOrThrow(PROJECTION[7])
        val fileNameColumn = data.getColumnIndexOrThrow(PROJECTION[8])
        val bucketIdColumn = data.getColumnIndexOrThrow(PROJECTION[9])
        val dateAddedColumn = data.getColumnIndexOrThrow(PROJECTION[10])
        val orientationColumn = data.getColumnIndexOrThrow(PROJECTION[11])
        val id = data.getLong(idColumn)
        val dateAdded = data.getLong(dateAddedColumn)
        var mimeType = data.getString(mimeTypeColumn)
        val absolutePath = data.getString(dataColumn)
        val url = if (SdkVersionUtils.isQ()) MediaUtils.getRealPathUri(id, mimeType) else absolutePath
        mimeType = if (TextUtils.isEmpty(mimeType)) PictureMimeType.ofJPEG() else mimeType
        // Here, it is solved that some models obtain mimeType and return the format of image / *,
        // which makes it impossible to distinguish the specific type, such as mi 8,9,10 and other models
        if (mimeType!!.endsWith("image/*")) {
            mimeType = MediaUtils.getMimeTypeFromMediaUrl(absolutePath!!)
            if (!getConfig().isGif) {
                if (PictureMimeType.isHasGif(mimeType)) {
                    return null
                }
            }
        }

        if (mimeType.endsWith("image/*")) {
            return null
        }

        if (!getConfig().isWebp) {
            if (mimeType.startsWith(PictureMimeType.ofWEBP())) {
                return null
            }
        }
        if (!getConfig().isBmp) {
            if (PictureMimeType.isHasBmp(mimeType)) {
                return null
            }
        }
        if (!getConfig().isHeic) {
            if (PictureMimeType.isHasHeic(mimeType)) {
                return null
            }
        }

        var width = data.getInt(widthColumn)
        var height = data.getInt(heightColumn)
        val orientation = data.getInt(orientationColumn)
        if (orientation == 90 || orientation == 270) {
            width = data.getInt(heightColumn)
            height = data.getInt(widthColumn)
        }
        val duration = data.getLong(durationColumn)
        val size = data.getLong(sizeColumn)
        val folderName = data.getString(folderNameColumn)
        var fileName = data.getString(fileNameColumn)
        val bucketId = data.getLong(bucketIdColumn)
        if (TextUtils.isEmpty(fileName)) {
            fileName = PictureMimeType.getUrlToFileName(absolutePath)
        }
        if (getConfig().isFilterSizeDuration && size > 0 && size < FileSizeUnit.KB) {
            // Filter out files less than 1KB
            return null
        }
        if (PictureMimeType.isHasVideo(mimeType) || PictureMimeType.isHasAudio(mimeType)) {
            if (getConfig().filterVideoMinSecond > 0 && duration < getConfig().filterVideoMinSecond) {
                // If you set the minimum number of seconds of video to display
                return null
            }
            if (getConfig().filterVideoMaxSecond > 0 && duration > getConfig().filterVideoMaxSecond) {
                // If you set the maximum number of seconds of video to display
                return null
            }
            if (getConfig().isFilterSizeDuration && duration <= 0) {
                // If the length is 0, the corrupted video is processed and filtered out
                return null
            }
        }
        val media = LocalMedia.create()
        media.id = id
        media.bucketId = bucketId
        media.path = url.orEmpty()
        media.realPath = absolutePath.orEmpty()
        media.fileName = fileName.orEmpty()
        media.parentFolderName = folderName.orEmpty()
        media.duration = duration
        media.chooseModel = getConfig().chooseMode
        media.mimeType = mimeType.orEmpty()
        media.width = width
        media.height = height
        media.size = size
        media.dateAddedTime = dateAdded
        return media
    }

    /**
     * Create folder
     */
    private fun getImageFolder(
        firstPath: String?,
        firstMimeType: String?,
        folderName: String?,
        imageFolders: MutableList<LocalMediaFolder>,
    ): LocalMediaFolder {
        for (folder in imageFolders) {
            // Under the same folder, return yourself, otherwise create a new folder
            val name = folder.folderName
            if (TextUtils.isEmpty(name)) {
                continue
            }
            if (TextUtils.equals(name, folderName)) {
                return folder
            }
        }
        val newFolder = LocalMediaFolder()
        newFolder.folderName = folderName
        newFolder.firstImagePath = firstPath
        newFolder.firstMimeType = firstMimeType
        imageFolders.add(newFolder)
        return newFolder
    }

    companion object {
        /**
         * Video mode conditions
         */
        private fun getSelectionArgsForVideoMediaCondition(durationCondition: String, queryMimeCondition: String): String {
            return MediaStore.Files.FileColumns.MEDIA_TYPE + "=?" + queryMimeCondition + " AND " + durationCondition
        }

        /**
         * Audio mode conditions
         */
        private fun getSelectionArgsForAudioMediaCondition(durationCondition: String, queryMimeCondition: String): String {
            return MediaStore.Files.FileColumns.MEDIA_TYPE + "=?" + queryMimeCondition + " AND " + durationCondition
        }

        /**
         * Query conditions in all modes
         */
        private fun getSelectionArgsForAllMediaCondition(
            timeCondition: String,
            sizeCondition: String,
            queryImageMimeType: String,
            queryVideoMimeType: String,
        ): String {
            return "(" +
                MediaStore.Files.FileColumns.MEDIA_TYPE + "=?" + queryImageMimeType + " OR " +
                MediaStore.Files.FileColumns.MEDIA_TYPE + "=?" + queryVideoMimeType + " AND " +
                timeCondition + ") AND " +
                sizeCondition
        }

        /**
         * Query conditions in image modes
         */
        private fun getSelectionArgsForImageMediaCondition(fileSizeCondition: String, queryMimeCondition: String): String {
            return MediaStore.Files.FileColumns.MEDIA_TYPE + "=?" + queryMimeCondition + " AND " + fileSizeCondition
        }
    }
}
