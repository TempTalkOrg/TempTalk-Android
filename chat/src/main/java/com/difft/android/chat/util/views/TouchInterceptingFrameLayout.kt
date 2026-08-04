package com.difft.android.chat.util.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

class TouchInterceptingFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var listener: OnInterceptTouchEventListener? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return listener?.onInterceptTouchEvent(ev) ?: super.onInterceptTouchEvent(ev)
    }

    fun setOnInterceptTouchEventListener(listener: OnInterceptTouchEventListener?) {
        this.listener = listener
    }

    fun interface OnInterceptTouchEventListener {
        fun onInterceptTouchEvent(ev: MotionEvent): Boolean
    }
}
