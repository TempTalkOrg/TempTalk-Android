package com.difft.android.chat.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import java.util.Date

class TouchFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    private var longClickListener: OnLongClickListener? = null
    fun setLongClickListener(onLongClickListener: OnLongClickListener?) {
        this.longClickListener = onLongClickListener
    }

    var time: Long = 0
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return if (ev.action == MotionEvent.ACTION_DOWN) {
            time = Date().time
            super.onInterceptTouchEvent(ev)
        } else {
            val current = Date().time - time
            if (current < 300) {
                false
            } else { //大于1000视为长按
                longClickListener?.onLongClick(this) //长按事件
                true
            }
        }
    }
}