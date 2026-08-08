package com.difft.android.selector.entity

import android.os.Parcelable
import android.text.TextUtils
import com.difft.android.selector.config.PictureConfig
import kotlinx.parcelize.Parcelize
import java.util.ArrayList

/**
 * [folderNameField] / [dataField] back the fallback-applying [folderName] / [data] accessors and
 * are the values actually parceled (1:1 with the original writeToParcel). The list element type
 * is parceled by @Parcelize without an explicit LocalMedia.CREATOR reference.
 */
@Parcelize
class LocalMediaFolder(
    /** folder bucketId */
    var bucketId: Long = PictureConfig.ALL.toLong(),
    private var folderNameField: String? = null,
    /** folder first path */
    var firstImagePath: String? = null,
    /** first data mime type */
    var firstMimeType: String? = null,
    /** folder total media num */
    var folderTotalNum: Int = 0,
    /** there are selected resources in the current directory */
    var isSelectTag: Boolean = false,
    private var dataField: ArrayList<LocalMedia>? = ArrayList(),
    /** internal use: current data page */
    var currentDataPage: Int = 1,
    /** internal use: is there more to load */
    var isHasMore: Boolean = false,
) : Parcelable {

    /** folder name; falls back to "unknown" when empty (1:1 with the Java getter). */
    var folderName: String?
        get() = if (TextUtils.isEmpty(folderNameField)) "unknown" else folderNameField
        set(value) {
            folderNameField = value
        }

    /**
     * current folder data; getter never returns null (1:1 with the Java getter).
     * In isPageStrategy mode there is no data on the first pass.
     */
    var data: ArrayList<LocalMedia>?
        get() = if (dataField != null) dataField else ArrayList()
        set(value) {
            dataField = value
        }
}
