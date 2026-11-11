package com.difft.android.selector.engine

import android.content.Context

import com.difft.android.selector.interfaces.OnKeyValueResultCallbackListener

interface UriToFileTransformEngine {
    



    fun onUriToFileAsyncTransform(
        context: Context,
        srcPath: String,
        mineType: String,
        call: OnKeyValueResultCallbackListener
    )
}
