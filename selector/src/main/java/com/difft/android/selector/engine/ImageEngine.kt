package com.difft.android.selector.engine

import android.content.Context
import android.widget.ImageView

interface ImageEngine {
    fun loadImage(context: Context, url: String?, imageView: ImageView)

    fun loadImage(context: Context, imageView: ImageView, url: String?, maxWidth: Int, maxHeight: Int)

    fun loadAlbumCover(context: Context, url: String?, imageView: ImageView)

    fun loadGridImage(context: Context, url: String?, imageView: ImageView)

    /** Pause resource loading while the RecyclerView is scrolling fast. */
    fun pauseRequests(context: Context)

    /** Resume resource loading when scrolling slows or stops. */
    fun resumeRequests(context: Context)
}
