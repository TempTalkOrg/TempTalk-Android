package com.difft.android.selector.photoview

import android.widget.ImageView

fun interface OnPhotoTapListener {

    fun onPhotoTap(view: ImageView, x: Float, y: Float)
}
