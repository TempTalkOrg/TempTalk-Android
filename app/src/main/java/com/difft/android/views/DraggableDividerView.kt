package com.difft.android.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.difft.android.R
import kotlin.math.abs




















class DraggableDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    










    var onDrag: ((delta: Int, isEnd: Boolean, velocityX: Float, cancelled: Boolean) -> Unit)? = null

    private var lastX: Float = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var movedBeyondSlop = false
    private var velocityTracker: android.view.VelocityTracker? = null
    private val activeBandHeightPx by lazy {
        (ACTIVE_BAND_HEIGHT_DP * resources.displayMetrics.density).toInt()
    }

    init {
        if (background == null) {
            background = ContextCompat.getDrawable(context, R.drawable.bg_draggable_divider)
        }
        if (contentDescription == null) {
            contentDescription = context.getString(R.string.dual_pane_divider_drag_hint)
        }
        
        isClickable = true
    }

    



    private fun addRawMovement(event: MotionEvent) {
        val tracker = velocityTracker ?: return
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    



    private fun isInActiveBand(y: Float): Boolean {
        if (height <= activeBandHeightPx) return true
        val halfBand = activeBandHeightPx / 2
        val centerY = height / 2
        return y >= (centerY - halfBand) && y <= (centerY + halfBand)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            if (!isInActiveBand(event.y)) {
                
                
                false
            } else {
                lastX = event.rawX
                movedBeyondSlop = false
                isPressed = true
                velocityTracker?.recycle()
                velocityTracker = android.view.VelocityTracker.obtain()
                addRawMovement(event)
                
                parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
        }

        MotionEvent.ACTION_MOVE -> {
            addRawMovement(event)
            if (!movedBeyondSlop) {
                if (abs(event.rawX - lastX) > touchSlop) {
                    movedBeyondSlop = true
                    
                    
                    
                    lastX = event.rawX
                }
            } else {
                val delta = (event.rawX - lastX).toInt()
                if (delta != 0) {
                    onDrag?.invoke(delta, false, 0f, false)
                    lastX = event.rawX
                }
            }
            true
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            isPressed = false
            parent?.requestDisallowInterceptTouchEvent(false)
            val cancelled = event.action == MotionEvent.ACTION_CANCEL
            val velocityX = if (!cancelled) {
                addRawMovement(event)
                velocityTracker?.run {
                    computeCurrentVelocity(1000)
                    xVelocity
                } ?: 0f
            } else {
                0f
            }
            velocityTracker?.recycle()
            velocityTracker = null
            if (movedBeyondSlop) {
                onDrag?.invoke(0, true, velocityX, cancelled)
            } else if (event.action == MotionEvent.ACTION_UP) {
                
                
                performClick()
            }
            true
        }

        else -> false
    }

    private companion object {
        
        const val ACTIVE_BAND_HEIGHT_DP = 200
    }
}
