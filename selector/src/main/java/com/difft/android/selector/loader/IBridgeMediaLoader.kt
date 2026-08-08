package com.difft.android.selector.loader

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.entity.LocalMediaFolder
import com.difft.android.selector.interfaces.OnQueryAlbumListener
import com.difft.android.selector.interfaces.OnQueryAllAlbumListener
import com.difft.android.selector.interfaces.OnQueryDataResultListener
import java.util.Locale

abstract class IBridgeMediaLoader(context: Context, config: SelectorConfig) {

    private val mContext: Context = context
    protected val mConfig: SelectorConfig = config

    protected fun getContext(): Context = mContext

    protected fun getConfig(): SelectorConfig = mConfig

    /**
     * query album cover
     */
    abstract fun getAlbumFirstCover(bucketId: Long): String?

    /**
     * query album list
     */
    abstract fun loadAllAlbum(query: OnQueryAllAlbumListener<LocalMediaFolder>?)

    /**
     * page query specified contents
     */
    abstract fun loadPageMediaData(
        bucketId: Long,
        page: Int,
        pageSize: Int,
        query: OnQueryDataResultListener<LocalMedia>?,
    )

    /**
     * query specified contents
     */
    abstract fun loadOnlyInAppDirAllMedia(query: OnQueryAlbumListener<LocalMediaFolder?>?)

    /**
     * A filter declaring which rows to return,
     * formatted as an SQL WHERE clause (excluding the WHERE itself).
     * Passing null will return all rows for the given URI.
     */
    protected abstract fun getSelection(): String?

    /**
     * You may include ?s in selection, which will be replaced by the values from selectionArgs,
     * in the order that they appear in the selection. The values will be bound as Strings.
     */
    protected abstract fun getSelectionArgs(): Array<String>?

    /**
     * How to order the rows, formatted as an SQL ORDER BY clause (excluding the ORDER BY itself).
     */
    protected abstract fun getSortOrder(): String

    /**
     * parse LocalMedia
     */
    protected abstract fun parseLocalMedia(data: Cursor, isUsePool: Boolean): LocalMedia?

    /**
     * Get video (maximum or minimum time)
     */
    protected fun getDurationCondition(): String {
        val maxS =
            if (getConfig().filterVideoMaxSecond == 0) Long.MAX_VALUE else getConfig().filterVideoMaxSecond.toLong()
        return String.format(
            Locale.CHINA,
            "%d <%s $COLUMN_DURATION and $COLUMN_DURATION <= %d",
            Math.max(0L, getConfig().filterVideoMinSecond.toLong()), "=", maxS,
        )
    }

    /**
     * Get media size (maxFileSize or miniFileSize)
     */
    protected fun getFileSizeCondition(): String {
        val maxS =
            if (getConfig().filterMaxFileSize == 0L) Long.MAX_VALUE else getConfig().filterMaxFileSize
        return String.format(
            Locale.CHINA,
            "%d <%s " + MediaStore.MediaColumns.SIZE + " and " + MediaStore.MediaColumns.SIZE + " <= %d",
            Math.max(0L, getConfig().filterMinFileSize), "=", maxS,
        )
    }

    protected fun getImageMimeTypeCondition(): String {
        val filters = getConfig().queryOnlyImageList
        val stringBuilder = StringBuilder()
        for (i in filters.indices) {
            val mimeType = filters[i]
            stringBuilder.append(if (i == 0) " AND " else " OR ")
                .append(MediaStore.MediaColumns.MIME_TYPE).append("='").append(mimeType)
                .append("'")
        }
        if (!getConfig().isGif && !getConfig().queryOnlyImageList.contains(PictureMimeType.ofGIF())) {
            stringBuilder.append(NOT_GIF)
        }
        if (!getConfig().isWebp && !getConfig().queryOnlyImageList.contains(PictureMimeType.ofWEBP())) {
            stringBuilder.append(NOT_WEBP)
        }
        if (!getConfig().isBmp && !getConfig().queryOnlyImageList.contains(PictureMimeType.ofBMP())
            && !getConfig().queryOnlyImageList.contains(PictureMimeType.ofXmsBMP())
            && !getConfig().queryOnlyImageList.contains(PictureMimeType.ofWapBMP())
        ) {
            stringBuilder.append(NOT_BMP).append(NOT_XMS_BMP).append(NOT_VND_WAP_BMP)
        }
        if (!getConfig().isHeic && !getConfig().queryOnlyImageList.contains(PictureMimeType.ofHeic())) {
            stringBuilder.append(NOT_HEIC)
        }
        return stringBuilder.toString()
    }

    protected fun getVideoMimeTypeCondition(): String {
        val filters = getConfig().queryOnlyVideoList
        val stringBuilder = StringBuilder()
        for (i in filters.indices) {
            val mimeType = filters[i]
            stringBuilder.append(if (i == 0) " AND " else " OR ")
                .append(MediaStore.MediaColumns.MIME_TYPE).append("='").append(mimeType)
                .append("'")
        }
        return stringBuilder.toString()
    }

    protected fun getAudioMimeTypeCondition(): String {
        val filters = getConfig().queryOnlyAudioList
        val stringBuilder = StringBuilder()
        for (i in filters.indices) {
            val mimeType = filters[i]
            stringBuilder.append(if (i == 0) " AND " else " OR ")
                .append(MediaStore.MediaColumns.MIME_TYPE).append("='").append(mimeType)
                .append("'")
        }
        return stringBuilder.toString()
    }

    companion object {
        internal val QUERY_URI: Uri = MediaStore.Files.getContentUri("external")
        internal val ORDER_BY = MediaStore.MediaColumns.DATE_MODIFIED + " DESC"
        internal val NOT_GIF = " AND (" + MediaStore.MediaColumns.MIME_TYPE + "!='image/gif')"
        internal val NOT_WEBP = " AND (" + MediaStore.MediaColumns.MIME_TYPE + "!='image/webp')"
        internal val NOT_BMP = " AND (" + MediaStore.MediaColumns.MIME_TYPE + "!='image/bmp')"
        internal val NOT_XMS_BMP = " AND (" + MediaStore.MediaColumns.MIME_TYPE + "!='image/x-ms-bmp')"
        internal val NOT_VND_WAP_BMP = " AND (" + MediaStore.MediaColumns.MIME_TYPE + "!='image/vnd.wap.wbmp')"
        internal val NOT_HEIC = " AND (" + MediaStore.MediaColumns.MIME_TYPE + "!='image/heic')"

        internal const val GROUP_BY_BUCKET_Id = " GROUP BY (bucket_id"
        internal const val COLUMN_COUNT = "count"
        internal const val COLUMN_BUCKET_ID = "bucket_id"
        internal const val COLUMN_DURATION = "duration"
        internal const val COLUMN_BUCKET_DISPLAY_NAME = "bucket_display_name"
        internal const val COLUMN_ORIENTATION = "orientation"
        internal const val MAX_SORT_SIZE = 60

        /**
         * A list of which columns to return. Passing null will return all columns, which is inefficient.
         */
        internal val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            COLUMN_DURATION,
            MediaStore.MediaColumns.SIZE,
            COLUMN_BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME,
            COLUMN_BUCKET_ID,
            MediaStore.MediaColumns.DATE_ADDED,
            COLUMN_ORIENTATION,
        )

        /**
         * A list of which columns to return. Passing null will return all columns, which is inefficient.
         */
        internal val ALL_PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            COLUMN_DURATION,
            MediaStore.MediaColumns.SIZE,
            COLUMN_BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME,
            COLUMN_BUCKET_ID,
            MediaStore.MediaColumns.DATE_ADDED,
            COLUMN_ORIENTATION,
            "COUNT(*) AS $COLUMN_COUNT",
        )
    }
}
