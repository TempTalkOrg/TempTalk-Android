package com.difft.android.selector.engine

import android.content.Context
import android.widget.ImageView

interface ImageEngine {
    fun loadImage(context: Context, url: String?, imageView: ImageView)

    fun loadImage(context: Context, imageView: ImageView, url: String?, maxWidth: Int, maxHeight: Int)

    fun loadAlbumCover(context: Context, url: String?, imageView: ImageView)

    fun loadGridImage(context: Context, url: String?, imageView: ImageView)

    
    fun pauseRequests(context: Context)

    
    fun resumeRequests(context: Context)
}
