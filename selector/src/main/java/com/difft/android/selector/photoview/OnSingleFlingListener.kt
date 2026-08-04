package com.difft.android.selector.photoview

import android.view.MotionEvent

fun interface OnSingleFlingListener {

    fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean
}
