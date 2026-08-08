package com.difft.android.selector.interfaces

import android.content.Context

import com.difft.android.selector.entity.LocalMedia

import java.util.ArrayList

interface OnPreviewInterceptListener {
    fun onPreview(
        context: Context,
        position: Int,
        totalNum: Int,
        page: Int,
        currentBucketId: Long,
        currentAlbumName: String,
        isShowCamera: Boolean,
        data: ArrayList<LocalMedia>,
        isBottomPreview: Boolean
    )
}
