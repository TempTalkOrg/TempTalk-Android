package com.difft.android.selector.entity

import android.os.Parcelable
import android.text.TextUtils
import com.difft.android.selector.config.PictureConfig
import kotlinx.parcelize.Parcelize
import java.util.ArrayList






@Parcelize
class LocalMediaFolder(
    
    var bucketId: Long = PictureConfig.ALL.toLong(),
    private var folderNameField: String? = null,
    
    var firstImagePath: String? = null,
    
    var firstMimeType: String? = null,
    
    var folderTotalNum: Int = 0,
    
    var isSelectTag: Boolean = false,
    private var dataField: ArrayList<LocalMedia>? = ArrayList(),
    
    var currentDataPage: Int = 1,
    
    var isHasMore: Boolean = false,
) : Parcelable {

    
    var folderName: String?
        get() = if (TextUtils.isEmpty(folderNameField)) "unknown" else folderNameField
        set(value) {
            folderNameField = value
        }

    



    var data: ArrayList<LocalMedia>?
        get() = if (dataField != null) dataField else ArrayList()
        set(value) {
            dataField = value
        }
}
