package com.difft.android.selector.utils

import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.entity.LocalMediaFolder

import java.util.Collections

object SortUtils {
    /** Sort by the number of files. */
    @JvmStatic
    fun sortFolder(imageFolders: MutableList<LocalMediaFolder>) {
        Collections.sort(imageFolders) { lhs, rhs ->
            if (lhs.data == null || rhs.data == null) {
                0
            } else {
                rhs.folderTotalNum.compareTo(lhs.folderTotalNum)
            }
        }
    }

    /** Sort by the added time of files. */
    @JvmStatic
    fun sortLocalMediaAddedTime(list: MutableList<LocalMedia>) {
        Collections.sort(list) { lhs, rhs ->
            rhs.dateAddedTime.compareTo(lhs.dateAddedTime)
        }
    }
}
