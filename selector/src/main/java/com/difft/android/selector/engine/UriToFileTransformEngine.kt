package com.difft.android.selector.engine

import android.content.Context

import com.difft.android.selector.interfaces.OnKeyValueResultCallbackListener

interface UriToFileTransformEngine {
    /**
     * Custom sandbox file engine (asynchronous). Implementers plug the sandbox path into the
     * LocalMedia object.
     */
    fun onUriToFileAsyncTransform(
        context: Context,
        srcPath: String,
        mineType: String,
        call: OnKeyValueResultCallbackListener
    )
}
