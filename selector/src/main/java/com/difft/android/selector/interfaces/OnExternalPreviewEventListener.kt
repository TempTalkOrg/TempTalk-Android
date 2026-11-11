package com.difft.android.selector.interfaces

import android.content.Context

import com.difft.android.selector.entity.LocalMedia

interface OnExternalPreviewEventListener {
    fun onPreviewDelete(position: Int)

    


    fun onLongPressDownload(context: Context?, media: LocalMedia?): Boolean
}
