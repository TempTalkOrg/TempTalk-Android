package com.difft.android.chat.group

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout







class TapPeekFrameLayout(context: Context) : FrameLayout(context) {

    var onPeek: ((MotionEvent) -> Boolean)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (onPeek?.invoke(ev) == true) ev.action = MotionEvent.ACTION_CANCEL
        return super.dispatchTouchEvent(ev)
    }
}
