package com.difft.android.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.difft.android.R
import kotlin.math.abs

/**
 * A vertical, draggable divider that reports horizontal drag deltas via [onDrag].
 *
 * Used between the list pane and the detail pane in the dual-pane layout
 * (`layout-w840dp-h480dp/activity_index.xml`). The view itself is ~24dp wide for
 * a comfortable touch target; the visual line + drag handle pill are rendered via
 * the background drawable.
 *
 * Touch is only accepted in a vertical center band ([ACTIVE_BAND_HEIGHT_DP]) so
 * accidental touches at the top / bottom of the long divider are ignored — only
 * a deliberate grab of the visible handle triggers a drag.
 *
 * The [onDrag] callback delivers:
 *   - `delta`: signed horizontal pixels since the last move (positive = drag right).
 *   - `isEnd`: true on ACTION_UP / ACTION_CANCEL (terminal frame).
 *
 * A touch-slop guard ensures small unintentional movements don't fire onDrag with
 * `isEnd=true`, mimicking platform behavior.
 */
class DraggableDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * Drag callback.
     * - During a drag, fires with the latest delta and `isEnd=false`.
     * - On ACTION_UP / ACTION_CANCEL after a drag, fires once with `delta=0, isEnd=true`.
     * - If the user's touch never crossed the touch-slop threshold, `isEnd` does not fire
     *   (treated as a tap, not a drag).
     */
    var onDrag: ((delta: Int, isEnd: Boolean) -> Unit)? = null

    private var lastX: Float = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var movedBeyondSlop = false
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
        // Make the view eligible for click feedback (ripple) without needing a click handler.
        isClickable = true
    }

    /**
     * True when [y] is inside the centered active band. View shorter than the band
     * (unusual but possible in landscape phone-sized windows) is treated as fully active.
     */
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
                // Touch fell outside the center drag handle area — ignore silently
                // (no ripple, no drag). User has to grab the visible handle to drag.
                false
            } else {
                lastX = event.rawX
                movedBeyondSlop = false
                isPressed = true
                // Block parent from intercepting (e.g., RecyclerView fling).
                parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
        }

        MotionEvent.ACTION_MOVE -> {
            if (!movedBeyondSlop) {
                if (abs(event.rawX - lastX) > touchSlop) {
                    movedBeyondSlop = true
                    // Reset lastX so the first emitted delta is just the incremental
                    // movement from this frame, not the accumulated slop distance —
                    // avoids a visible jump at drag start.
                    lastX = event.rawX
                }
            } else {
                val delta = (event.rawX - lastX).toInt()
                if (delta != 0) {
                    onDrag?.invoke(delta, false)
                    lastX = event.rawX
                }
            }
            true
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            isPressed = false
            parent?.requestDisallowInterceptTouchEvent(false)
            if (movedBeyondSlop) {
                onDrag?.invoke(0, true)
            }
            true
        }

        else -> false
    }

    private companion object {
        /** Vertical drag-active band centered in the view. Outside this band, touches are ignored. */
        const val ACTIVE_BAND_HEIGHT_DP = 200
    }
}
