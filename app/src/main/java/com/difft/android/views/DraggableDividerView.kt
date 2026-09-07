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
 * (`layout-w673dp-h480dp/activity_index.xml`). The view itself is ~24dp wide for
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
     * - During a drag, fires with the latest delta, `isEnd=false`, `velocityX=0`.
     * - On ACTION_UP after a drag, fires once with `delta=0, isEnd=true` and the gesture's
     *   horizontal fling velocity in px/s so the consumer can settle by position + velocity,
     *   like the platform pane-expansion handles.
     * - On ACTION_CANCEL after a drag, fires with `cancelled=true`: the system took the
     *   gesture away, so the consumer should roll back instead of committing a snap.
     * - If the user's touch never crossed the touch-slop threshold, `isEnd` does not fire
     *   (treated as a tap, not a drag).
     */
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
        // Make the view eligible for click feedback (ripple) without needing a click handler.
        isClickable = true
    }

    /**
     * Feed the tracker RAW screen coordinates: this view moves with the finger while the
     * pane resizes, so view-local X stays nearly constant and would read a ~0 velocity.
     */
    private fun addRawMovement(event: MotionEvent) {
        val tracker = velocityTracker ?: return
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
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
                velocityTracker?.recycle()
                velocityTracker = android.view.VelocityTracker.obtain()
                addRawMovement(event)
                // Block parent from intercepting (e.g., RecyclerView fling).
                parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
        }

        MotionEvent.ACTION_MOVE -> {
            addRawMovement(event)
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
                // A touch that never crossed slop is a tap — deliver it (expand-when-collapsed,
                // and the path TalkBack's activate action goes through).
                performClick()
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
