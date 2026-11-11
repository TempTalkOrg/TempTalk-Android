package com.difft.android.selector.engine

import android.content.Context

import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnCallbackIndexListener

@Deprecated("Use UriToFileTransformEngine")
interface SandboxFileEngine {
    




    fun onStartSandboxFileTransform(
        context: Context,
        isOriginalImage: Boolean,
        index: Int,
        media: LocalMedia,
        listener: OnCallbackIndexListener<LocalMedia>
    )
}
