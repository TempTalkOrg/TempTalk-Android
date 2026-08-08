package com.difft.android.selector.loader

import android.content.Context
import android.database.Cursor
import android.net.Uri
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
import com.difft.android.selector.entity.MediaData
import com.difft.android.selector.interfaces.OnQueryAlbumListener
import com.difft.android.selector.interfaces.OnQueryAllAlbumListener
import com.difft.android.selector.interfaces.OnQueryDataResultListener
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.ALL_PROJECTION
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.COLUMN_BUCKET_DISPLAY_NAME
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.COLUMN_BUCKET_ID
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.COLUMN_COUNT
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.GROUP_BY_BUCKET_Id
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.ORDER_BY
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.PROJECTION
import com.difft.android.selector.loader.IBridgeMediaLoader.Companion.QUERY_URI
import com.difft.android.selector.thread.PictureThreadUtils
import com.difft.android.selector.utils.MediaUtils
import com.difft.android.selector.utils.PictureFileUtils
import com.difft.android.selector.utils.SdkVersionUtils
import com.difft.android.selector.utils.SortUtils
import com.difft.android.selector.utils.ValueOf
import java.io.File
import java.util.ArrayList

/**
 * Local media database query class. Supports paging.
 *
 * 1:1 MediaStore cursor/paging port (issue #1077); structural split deferred — see #1077 future work.
 */
@Suppress("LargeClass")
class LocalMediaPageLoader(context: Context, config: SelectorConfig) : IBridgeMediaLoader(context, config) {

    /**
     * Query conditions in all modes
     */
    private fun getSelectionArgsForAllMediaCondition(
        timeCondition: String,
        sizeCondition: String,
        queryImageMimeType: String,
        queryVideoMimeType: String,
    ): String {
        val stringBuilder = StringBuilder()
        stringBuilder
            .append("(")
            .append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryImageMimeType)
            .append(" OR ")
            .append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryVideoMimeType)
            .append(" AND ")
            .append(timeCondition)
            .append(")")
            .append(" AND ")
            .append(sizeCondition)
        return if (isWithAllQuery()) {
            stringBuilder.toString()
        } else {
            stringBuilder.append(")").append(GROUP_BY_BUCKET_Id).toString()
        }
    }

    /**
     * Query conditions in image modes
     */
    private fun getSelectionArgsForImageMediaCondition(fileSizeCondition: String, queryMimeTypeOptions: String): String {
        val stringBuilder = StringBuilder()
        return if (isWithAllQuery()) {
            stringBuilder.append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?")
                .append(queryMimeTypeOptions).append(" AND ").append(fileSizeCondition).toString()
        } else {
            stringBuilder.append("(").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?")
                .append(queryMimeTypeOptions).append(") AND ").append(fileSizeCondition).append(")")
                .append(GROUP_BY_BUCKET_Id).toString()
        }
    }

    /**
     * Video mode conditions
     */
    private fun getSelectionArgsForVideoMediaCondition(durationCondition: String, queryMimeCondition: String): String {
        val stringBuilder = StringBuilder()
        return if (isWithAllQuery()) {
            stringBuilder.append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryMimeCondition).append(" AND ").append(durationCondition).toString()
        } else {
            stringBuilder.append("(").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryMimeCondition).append(") AND ").append(durationCondition).append(")").append(GROUP_BY_BUCKET_Id).toString()
        }
    }

    /**
     * Audio mode conditions
     */
    private fun getSelectionArgsForAudioMediaCondition(durationCondition: String, queryMimeCondition: String): String {
        val stringBuilder = StringBuilder()
        return if (isWithAllQuery()) {
            stringBuilder.append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryMimeCondition).append(" AND ").append(durationCondition).toString()
        } else {
            stringBuilder.append("(").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryMimeCondition).append(") AND ").append(durationCondition).append(")").append(GROUP_BY_BUCKET_Id).toString()
        }
    }

    override fun getAlbumFirstCover(bucketId: Long): String? {
        var data: Cursor? = null
        try {
            if (SdkVersionUtils.isR()) {
                val queryArgs = MediaUtils.createQueryArgsBundle(getPageSelection(bucketId)!!, getPageSelectionArgs(bucketId)!!, 1, 0, getSortOrder())
                data = getContext().contentResolver.query(
                    QUERY_URI,
                    arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.DATA,
                    ),
                    queryArgs, null,
                )
            } else {
                val orderBy = getSortOrder() + " limit 1 offset 0"
                data = getContext().contentResolver.query(
                    QUERY_URI,
                    arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.DATA,
                    ),
                    getPageSelection(bucketId), getPageSelectionArgs(bucketId), orderBy,
                )
            }
            if (data != null && data.count > 0) {
                if (data.moveToFirst()) {
                    val id = data.getLong(data.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    val mimeType = data.getString(data.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE))
                    return if (SdkVersionUtils.isQ()) {
                        MediaUtils.getRealPathUri(id, mimeType)
                    } else {
                        data.getString(data.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                    }
                }
                return null
            }
        } catch (e: Exception) {
            L.w(e) { "[LocalMediaPageLoader] getAlbumFirstCover error:" }
        } finally {
            if (data != null && !data.isClosed) {
                data.close()
            }
        }
        return null
    }

    override fun loadPageMediaData(
        bucketId: Long,
        page: Int,
        pageSize: Int,
        listener: OnQueryDataResultListener<LocalMedia>?,
    ) {
        PictureThreadUtils.executeByIo(object : PictureThreadUtils.SimpleTask<MediaData>() {

            override fun doInBackground(): MediaData {
                var data: Cursor? = null
                try {
                    if (SdkVersionUtils.isR()) {
                        val queryArgs = MediaUtils.createQueryArgsBundle(getPageSelection(bucketId)!!, getPageSelectionArgs(bucketId)!!, pageSize, (page - 1) * pageSize, getSortOrder())
                        data = getContext().contentResolver.query(QUERY_URI, PROJECTION, queryArgs, null)
                    } else {
                        val orderBy = if (page == PictureConfig.ALL) getSortOrder() else getSortOrder() + " limit " + pageSize + " offset " + (page - 1) * pageSize
                        data = getContext().contentResolver.query(QUERY_URI, PROJECTION, getPageSelection(bucketId), getPageSelectionArgs(bucketId), orderBy)
                    }
                    if (data != null) {
                        val result = ArrayList<LocalMedia>()
                        if (data.count > 0) {
                            data.moveToFirst()
                            do {
                                val media = parseLocalMedia(data, false) ?: continue
                                result.add(media)
                            } while (data.moveToNext())
                        }
                        if (bucketId == PictureConfig.ALL.toLong() && page == 1) {
                            val list = SandboxFileLoader.loadInAppSandboxFile(getContext(), getConfig().sandboxDir)
                            if (list != null) {
                                result.addAll(list)
                                SortUtils.sortLocalMediaAddedTime(result)
                            }
                        }
                        return MediaData(data.count > 0, result)
                    }
                } catch (e: Exception) {
                    L.w(e) { "[LocalMediaPageLoader] loadPageMediaData error:" }
                    L.i { "[LocalMediaPageLoader] loadMedia Page Data Error" + e }
                    return MediaData()
                } finally {
                    if (data != null && !data.isClosed) {
                        data.close()
                    }
                }
                return MediaData()
            }

            override fun onSuccess(result: MediaData) {
                PictureThreadUtils.cancel(this)
                listener?.onComplete(if (result.data != null) result.data!! else ArrayList(), result.isHasNextMore)
            }
        })
    }

    override fun loadOnlyInAppDirAllMedia(query: OnQueryAlbumListener<LocalMediaFolder?>?) {
        PictureThreadUtils.executeByIo(object : PictureThreadUtils.SimpleTask<LocalMediaFolder?>() {

            override fun doInBackground(): LocalMediaFolder? {
                return SandboxFileLoader.loadInAppSandboxFolderFile(getContext(), getConfig().sandboxDir)
            }

            override fun onSuccess(result: LocalMediaFolder?) {
                PictureThreadUtils.cancel(this)
                query?.onComplete(result)
            }
        })
    }

    /**
     * Query the local gallery data
     */
    override fun loadAllAlbum(query: OnQueryAllAlbumListener<LocalMediaFolder>?) {
        PictureThreadUtils.executeByIo(object : PictureThreadUtils.SimpleTask<List<LocalMediaFolder>>() {
            // 1:1 Java→Kotlin port (issue #1077); cursor/paging logic kept verbatim, structural split deferred.
            @Suppress("LongMethod")
            override fun doInBackground(): List<LocalMediaFolder> {
                val data = getContext().contentResolver.query(
                    QUERY_URI, if (isWithAllQuery()) PROJECTION else ALL_PROJECTION,
                    getSelection(), getSelectionArgs(), getSortOrder(),
                )
                try {
                    if (data != null) {
                        val count = data.count
                        var totalCount = 0
                        val mediaFolders: MutableList<LocalMediaFolder> = ArrayList()
                        if (count > 0) {
                            if (isWithAllQuery()) {
                                val countMap = HashMap<Long, Long>()
                                val hashSet = HashSet<Long>()
                                while (data.moveToNext()) {
                                    if (getConfig().isPageSyncAsCount) {
                                        val media = parseLocalMedia(data, true) ?: continue
                                        media.recycle()
                                    }
                                    val bucketId = data.getLong(data.getColumnIndexOrThrow(COLUMN_BUCKET_ID))
                                    val newCount = countMap[bucketId]
                                    if (newCount == null) {
                                        countMap[bucketId] = 1L
                                    } else {
                                        countMap[bucketId] = newCount + 1
                                    }

                                    if (hashSet.contains(bucketId)) {
                                        continue
                                    }
                                    val mediaFolder = LocalMediaFolder()
                                    mediaFolder.bucketId = bucketId
                                    val bucketDisplayName = data.getString(
                                        data.getColumnIndexOrThrow(COLUMN_BUCKET_DISPLAY_NAME),
                                    )
                                    val mimeType = data.getString(data.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                                    if (!countMap.containsKey(bucketId)) {
                                        continue
                                    }
                                    val size = countMap[bucketId]!!
                                    val id = data.getLong(data.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                                    mediaFolder.folderName = bucketDisplayName
                                    mediaFolder.folderTotalNum = ValueOf.toInt(size)
                                    mediaFolder.firstImagePath = MediaUtils.getRealPathUri(id, mimeType)
                                    mediaFolder.firstMimeType = mimeType
                                    mediaFolders.add(mediaFolder)
                                    hashSet.add(bucketId)
                                }
                                for (mediaFolder in mediaFolders) {
                                    val size = ValueOf.toInt(countMap[mediaFolder.bucketId])
                                    mediaFolder.folderTotalNum = size
                                    totalCount += size
                                }
                            } else {
                                data.moveToFirst()
                                do {
                                    val url = data.getString(data.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                                    val bucketDisplayName = data.getString(data.getColumnIndexOrThrow(COLUMN_BUCKET_DISPLAY_NAME))
                                    val mimeType = data.getString(data.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                                    val bucketId = data.getLong(data.getColumnIndexOrThrow(COLUMN_BUCKET_ID))
                                    val size = data.getInt(data.getColumnIndexOrThrow(COLUMN_COUNT))
                                    val mediaFolder = LocalMediaFolder()
                                    mediaFolder.bucketId = bucketId
                                    mediaFolder.firstImagePath = url
                                    mediaFolder.folderName = bucketDisplayName
                                    mediaFolder.firstMimeType = mimeType
                                    mediaFolder.folderTotalNum = size
                                    mediaFolders.add(mediaFolder)
                                    totalCount += size
                                } while (data.moveToNext())
                            }
                            // 相机胶卷
                            val allMediaFolder = LocalMediaFolder()
                            val selfFolder = SandboxFileLoader
                                .loadInAppSandboxFolderFile(getContext(), getConfig().sandboxDir)
                            if (selfFolder != null) {
                                mediaFolders.add(selfFolder)
                                val firstImagePath = selfFolder.firstImagePath
                                val file = File(firstImagePath!!)
                                val lastModified = file.lastModified()
                                totalCount += selfFolder.folderTotalNum
                                allMediaFolder.data = ArrayList()
                                if (data.moveToFirst()) {
                                    allMediaFolder.firstImagePath = if (SdkVersionUtils.isQ()) getFirstUri(data) else getFirstUrl(data)
                                    allMediaFolder.firstMimeType = getFirstCoverMimeType(data)
                                    val lastModified2: Long
                                    if (PictureMimeType.isContent(allMediaFolder.firstImagePath)) {
                                        val path = PictureFileUtils.getPath(getContext(), Uri.parse(allMediaFolder.firstImagePath))
                                        lastModified2 = File(path!!).lastModified()
                                    } else {
                                        lastModified2 = File(allMediaFolder.firstImagePath!!).lastModified()
                                    }
                                    if (lastModified > lastModified2) {
                                        allMediaFolder.firstImagePath = selfFolder.firstImagePath
                                        allMediaFolder.firstMimeType = selfFolder.firstMimeType
                                    }
                                }
                            } else {
                                if (data.moveToFirst()) {
                                    allMediaFolder.firstImagePath = if (SdkVersionUtils.isQ()) getFirstUri(data) else getFirstUrl(data)
                                    allMediaFolder.firstMimeType = getFirstCoverMimeType(data)
                                }
                            }
                            if (totalCount == 0) {
                                return mediaFolders
                            }
                            SortUtils.sortFolder(mediaFolders)
                            allMediaFolder.folderTotalNum = totalCount
                            allMediaFolder.bucketId = PictureConfig.ALL.toLong()
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
                            allMediaFolder.folderName = folderName
                            mediaFolders.add(0, allMediaFolder)
                            if (getConfig().isSyncCover) {
                                if (getConfig().chooseMode == SelectMimeType.ofAll()) {
                                    synchronousFirstCover(mediaFolders)
                                }
                            }
                            return mediaFolders
                        }
                    }
                } catch (e: Exception) {
                    L.w(e) { "[LocalMediaPageLoader] loadAllAlbum error:" }
                    L.i { "[LocalMediaPageLoader] loadAllMedia Data Error" + e }
                } finally {
                    if (data != null && !data.isClosed) {
                        data.close()
                    }
                }
                return ArrayList()
            }

            override fun onSuccess(result: List<LocalMediaFolder>) {
                PictureThreadUtils.cancel(this)
                LocalMedia.destroyPool()
                query?.onComplete(result)
            }
        })
    }

    /**
     * Synchronous first data cover
     */
    private fun synchronousFirstCover(mediaFolders: List<LocalMediaFolder>) {
        for (i in mediaFolders.indices) {
            val mediaFolder = mediaFolders[i]
            val firstCover = getAlbumFirstCover(mediaFolder.bucketId)
            if (TextUtils.isEmpty(firstCover)) {
                continue
            }
            mediaFolder.firstImagePath = firstCover
        }
    }

    private fun getPageSelection(bucketId: Long): String? {
        val durationCondition = getDurationCondition()
        val sizeCondition = getFileSizeCondition()
        return when (getConfig().chooseMode) {
            SelectMimeType.TYPE_ALL ->
                getPageSelectionArgsForAllMediaCondition(bucketId, getImageMimeTypeCondition(), getVideoMimeTypeCondition(), durationCondition, sizeCondition)

            SelectMimeType.TYPE_IMAGE ->
                getPageSelectionArgsForImageMediaCondition(bucketId, getImageMimeTypeCondition(), sizeCondition)

            SelectMimeType.TYPE_VIDEO ->
                getPageSelectionArgsForVideoMediaCondition(bucketId, getVideoMimeTypeCondition(), durationCondition, sizeCondition)

            SelectMimeType.TYPE_AUDIO ->
                getPageSelectionArgsForAudioMediaCondition(bucketId, getAudioMimeTypeCondition(), durationCondition, sizeCondition)

            else -> null
        }
    }

    private fun getPageSelectionArgs(bucketId: Long): Array<String>? {
        return when (getConfig().chooseMode) {
            SelectMimeType.TYPE_ALL -> if (bucketId == PictureConfig.ALL.toLong()) {
                // ofAll
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                )
            } else {
                //  Gets the specified album directory
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                    ValueOf.toString(bucketId),
                )
            }

            SelectMimeType.TYPE_IMAGE ->
                // Get photo
                getSelectionArgsForPageSingleMediaType(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, bucketId)

            SelectMimeType.TYPE_VIDEO ->
                // Get video
                getSelectionArgsForPageSingleMediaType(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, bucketId)

            SelectMimeType.TYPE_AUDIO ->
                // Get audio
                getSelectionArgsForPageSingleMediaType(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO, bucketId)

            else -> null
        }
    }

    override fun getSelection(): String? {
        val durationCondition = getDurationCondition()
        val fileSizeCondition = getFileSizeCondition()
        return when (getConfig().chooseMode) {
            SelectMimeType.TYPE_ALL ->
                // Get all, not including audio
                getSelectionArgsForAllMediaCondition(
                    durationCondition, fileSizeCondition,
                    getImageMimeTypeCondition(), getVideoMimeTypeCondition(),
                )

            SelectMimeType.TYPE_IMAGE ->
                // Get Images
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

    /**
     * Query strategy
     */
    private fun isWithAllQuery(): Boolean {
        return if (SdkVersionUtils.isQ()) {
            true
        } else {
            getConfig().isPageSyncAsCount
        }
    }

    // 1:1 Java→Kotlin port (issue #1077); cursor column parsing kept verbatim, structural split deferred.
    @Suppress("LongMethod")
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
        var mimeType = data.getString(mimeTypeColumn)
        val absolutePath = data.getString(dataColumn)
        val url = if (SdkVersionUtils.isQ()) MediaUtils.getRealPathUri(id, mimeType) else absolutePath
        mimeType = if (TextUtils.isEmpty(mimeType)) PictureMimeType.ofJPEG() else mimeType
        if (getConfig().isFilterInvalidFile) {
            if (PictureMimeType.isHasImage(mimeType)) {
                if (!TextUtils.isEmpty(absolutePath) && !PictureFileUtils.isImageFileExists(absolutePath!!)) {
                    return null
                }
            } else {
                if (!PictureFileUtils.isFileExists(absolutePath)) {
                    return null
                }
            }
        }
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
        val dateAdded = data.getLong(dateAddedColumn)
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
        val media = if (isUsePool) LocalMedia.obtain() else LocalMedia.create()
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

    companion object {
        /**
         * Gets a file of the specified type
         */
        private fun getSelectionArgsForPageSingleMediaType(mediaType: Int, bucketId: Long): Array<String> {
            return if (bucketId == PictureConfig.ALL.toLong()) {
                arrayOf(mediaType.toString())
            } else {
                arrayOf(mediaType.toString(), ValueOf.toString(bucketId))
            }
        }

        private fun getPageSelectionArgsForAllMediaCondition(
            bucketId: Long,
            queryImageMimeType: String,
            queryVideoMimeType: String,
            durationCondition: String,
            sizeCondition: String,
        ): String {
            val stringBuilder = StringBuilder()
            stringBuilder.append("(")
                .append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryImageMimeType)
                .append(" OR ")
                .append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryVideoMimeType)
                .append(" AND ")
                .append(durationCondition)
                .append(")")
                .append(" AND ")
            return if (bucketId == PictureConfig.ALL.toLong()) {
                stringBuilder.append(sizeCondition).toString()
            } else {
                stringBuilder.append(COLUMN_BUCKET_ID).append("=? AND ").append(sizeCondition).toString()
            }
        }

        private fun getPageSelectionArgsForImageMediaCondition(bucketId: Long, queryMimeCondition: String, sizeCondition: String): String {
            val stringBuilder = StringBuilder()
            stringBuilder.append("(").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?")
            return if (bucketId == PictureConfig.ALL.toLong()) {
                stringBuilder.append(queryMimeCondition).append(") AND ").append(sizeCondition).toString()
            } else {
                stringBuilder.append(queryMimeCondition).append(") AND ").append(COLUMN_BUCKET_ID).append("=? AND ").append(sizeCondition).toString()
            }
        }

        private fun getPageSelectionArgsForVideoMediaCondition(bucketId: Long, queryMimeCondition: String, durationCondition: String, sizeCondition: String): String {
            val stringBuilder = StringBuilder()
            stringBuilder.append("(").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryMimeCondition).append(" AND ").append(durationCondition).append(") AND ")
            return if (bucketId == PictureConfig.ALL.toLong()) {
                stringBuilder.append(sizeCondition).toString()
            } else {
                stringBuilder.append(COLUMN_BUCKET_ID).append("=? AND ").append(sizeCondition).toString()
            }
        }

        private fun getPageSelectionArgsForAudioMediaCondition(bucketId: Long, queryMimeCondition: String, durationCondition: String, sizeCondition: String): String {
            val stringBuilder = StringBuilder()
            stringBuilder.append("(").append(MediaStore.Files.FileColumns.MEDIA_TYPE).append("=?").append(queryMimeCondition).append(" AND ").append(durationCondition).append(") AND ")
            return if (bucketId == PictureConfig.ALL.toLong()) {
                stringBuilder.append(sizeCondition).toString()
            } else {
                stringBuilder.append(COLUMN_BUCKET_ID).append("=? AND ").append(sizeCondition).toString()
            }
        }

        /**
         * Get cover uri
         */
        private fun getFirstUri(cursor: Cursor): String {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
            val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE))
            return MediaUtils.getRealPathUri(id, mimeType)
        }

        /**
         * Get cover uri mimeType
         */
        private fun getFirstCoverMimeType(cursor: Cursor): String? {
            return cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE))
        }

        /**
         * Get cover url
         */
        private fun getFirstUrl(cursor: Cursor): String? {
            return cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA))
        }
    }
}
