package com.difft.android.selector.engine

import android.content.Context

import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnCallbackIndexListener

@Deprecated("Use UriToFileTransformEngine")
interface SandboxFileEngine {
    /**
     * Custom sandbox file engine. Implementers plug the sandbox path into the LocalMedia object.
     *
     * @param index the location of the resource in the result queue
     */
    fun onStartSandboxFileTransform(
        context: Context,
        isOriginalImage: Boolean,
        index: Int,
        media: LocalMedia,
        listener: OnCallbackIndexListener<LocalMedia>
    )
}
