package com.difft.android.selector.engine

import com.difft.android.selector.basic.IBridgeLoaderFactory
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnInjectLayoutResourceListener
import com.difft.android.selector.interfaces.OnResultCallbackListener

interface PictureSelectorEngine {
    fun createImageLoaderEngine(): ImageEngine

    fun createCompressEngine(): CompressEngine

    fun createCompressFileEngine(): CompressFileEngine

    fun createLoaderDataEngine(): ExtendLoaderEngine

    fun createVideoPlayerEngine(): VideoPlayerEngine<*>

    fun onCreateLoader(): IBridgeLoaderFactory

    fun createSandboxFileEngine(): SandboxFileEngine

    fun createUriToFileTransformEngine(): UriToFileTransformEngine

    fun createLayoutResourceListener(): OnInjectLayoutResourceListener

    fun getResultCallbackListener(): OnResultCallbackListener<LocalMedia>
}
