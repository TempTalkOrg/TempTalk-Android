package com.difft.android.chat.ui.textpreview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import com.difft.android.base.log.lumberjack.L

/**
 * Custom TextView for text preview.
 * Now primarily used with custom TextSelectionManager for selection handling.
 * Blocks double-tap to prevent accidental word selection when using system selection.
 */
class SelectableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var onSelectionChangedListener: ((Int, Int) -> Unit)? = null

    private var lastDownTime = 0L
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var isDoubleTap = false
    
    companion object {
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val DOUBLE_TAP_SLOP = 100f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val currentTime = System.currentTimeMillis()
                val dx = event.x - lastDownX
                val dy = event.y - lastDownY

                // Check if this is a double-tap
                isDoubleTap = (currentTime - lastDownTime < DOUBLE_TAP_TIMEOUT) &&
                        (dx * dx + dy * dy < DOUBLE_TAP_SLOP * DOUBLE_TAP_SLOP)

                lastDownTime = currentTime
                lastDownX = event.x
                lastDownY = event.y

                // If double-tap detected, consume to prevent word selection
                if (isDoubleTap) {
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDoubleTap) {
                    isDoubleTap = false
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDoubleTap) {
                    return true
                }
            }
        }

        return try {
            super.onTouchEvent(event)
        } catch (e: NullPointerException) {
            // Handle NPE when Editor is not ready
            L.w { "[SelectableTextView] onTouchEvent NPE when Editor not ready: ${e.message}" }
            true
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChangedListener?.invoke(selStart, selEnd)
    }
    
    override fun performLongClick(): Boolean {
        return try {
            super.performLongClick()
        } catch (e: NullPointerException) {
            L.w { "[SelectableTextView] performLongClick NPE: ${e.message}" }
            true
        }
    }
    
    override fun performLongClick(x: Float, y: Float): Boolean {
        return try {
            super.performLongClick(x, y)
        } catch (e: NullPointerException) {
            L.w { "[SelectableTextView] performLongClick(x,y) NPE: ${e.message}" }
            true
        }
    }
}