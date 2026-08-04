package com.difft.android.selector.engine

import android.content.Context

import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnCallbackListener

import java.util.ArrayList

@Deprecated("Please use CompressFileEngine")
interface CompressEngine {
    fun onStartCompress(
        context: Context,
        list: ArrayList<LocalMedia>,
        listener: OnCallbackListener<ArrayList<LocalMedia>>
    )
}
