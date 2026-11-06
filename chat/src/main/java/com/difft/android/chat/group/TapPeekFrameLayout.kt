package com.difft.android.chat.group

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Page root that shows every touch to [onPeek] before its children, the way an Activity's
 * `dispatchTouchEvent` override does for a whole window. When [onPeek] consumes the gesture the
 * event reaches the children as ACTION_CANCEL, so a row underneath releases its pressed state
 * without firing its click. Used by GroupInfoFragment for the edit-mode outside-tap check.
 */
class TapPeekFrameLayout(context: Context) : FrameLayout(context) {

    var onPeek: ((MotionEvent) -> Boolean)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (onPeek?.invoke(ev) == true) ev.action = MotionEvent.ACTION_CANCEL
        return super.dispatchTouchEvent(ev)
    }
}
