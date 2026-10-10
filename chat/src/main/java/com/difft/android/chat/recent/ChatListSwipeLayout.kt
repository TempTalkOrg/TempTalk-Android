package com.difft.android.chat.recent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.difft.android.chat.R
import kotlin.math.abs

/**
 * Swipe-to-reveal row for the chat list (chat-list gesture design).
 *
 * Children by id: `swipe_start_actions` (revealed by swiping towards the end edge, one button),
 * `swipe_end_actions` (revealed by swiping towards the start edge, pin + delete) and
 * `swipe_foreground` (the row). Geometry is expressed in layout direction, so RTL mirrors.
 *
 * - horizontal travel over the touch slop and larger than the vertical travel starts a swipe; until
 *   then the list scrolls and the row's own click / long-press stay live
 * - on release: at least half of the action width revealed → snap open, otherwise spring back
 * - once the start button is revealed beyond its own width the uncovered area is painted in the
 *   button colour; past [FULL_SWIPE_FRACTION] of the row width the swipe is armed (haptic) and
 *   release runs [onFullSwipeStart] (reversible actions only)
 * - an open row closes on a tap on its foreground (the tap is consumed) while a tap on a revealed
 *   button reaches that button; a vertical drag on an open row scrolls the list as usual
 *
 * The adapter keeps at most one row open through [callback].
 */
class ChatListSwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Callback {
        fun onOpened(layout: ChatListSwipeLayout)
        fun onClosed(layout: ChatListSwipeLayout)
    }

    /** Disabling while open closes the row, otherwise it would stay revealed and untappable. */
    var swipeEnabled: Boolean = true
        set(value) {
            field = value
            if (!value && ::foreground.isInitialized && isOpen) close(animate = false)
        }
    var callback: Callback? = null

    /** Runs when the start button is revealed past [FULL_SWIPE_FRACTION] of the row and released. */
    var onFullSwipeStart: (() -> Unit)? = null

    private lateinit var startActions: View
    private lateinit var endActions: View
    private lateinit var foreground: View

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val actionWidthPx = resources.getDimensionPixelSize(R.dimen.chat_list_swipe_action_width)
    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var dragging = false
    private var closeOnUp = false
    private var fullSwipeArmed = false
    private var animator: ValueAnimator? = null

    /** Where the running animation is heading; `null` when nothing animates. */
    private var animationTarget: Float? = null

    /**
     * Target of an animation that a touch cancelled before any drag began. A touch that then
     * ends without dragging (tap, or the list taking the gesture for a vertical scroll) resumes
     * it, so a closing row keeps closing instead of being re-judged by the snap-open rule.
     */
    private var interruptedTarget: Float? = null

    /** +1 for LTR, -1 for RTL: converts between logical "reveal" offsets and translationX. */
    private val dir: Float
        get() = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f

    /** Logical reveal: > 0 shows the start button, < 0 the end buttons. */
    private val offset: Float
        get() = foreground.translationX * dir

    val isOpen: Boolean
        get() = offset != 0f

    override fun onFinishInflate() {
        super.onFinishInflate()
        startActions = findViewById(R.id.swipe_start_actions)
        endActions = findViewById(R.id.swipe_end_actions)
        foreground = findViewById(R.id.swipe_foreground)
    }

    private val startWidth: Int
        get() = if (startActions.isVisible) actionWidthPx else 0

    private val endWidth: Int
        get() = if (endActions.isVisible) endActions.measuredWidth else 0

    private val fullSwipeAvailable: Boolean
        get() = startActions.isVisible && onFullSwipeStart != null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimation()
                downX = ev.x
                downY = ev.y
                startOffset = offset
                dragging = false
                fullSwipeArmed = false
                // An open row: a tap on the foreground only closes it (consumed on UP below); a
                // tap on a revealed button must still reach that button.
                closeOnUp = isOpen && !isOnRevealedAction(ev.x)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    interruptedTarget = null // the drag decides the new rest position
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasCloseTap = closeOnUp && !dragging && ev.actionMasked == MotionEvent.ACTION_UP
                closeOnUp = false
                if (wasCloseTap) {
                    close()
                    return true // the foreground gets CANCEL instead of a click
                }
                // A touch that cancelled a snap animation and never became a drag (e.g. the list
                // took over for a vertical scroll) leaves the row part-way: let it settle.
                if (!dragging) settleIfPartial()
            }
        }
        return dragging
    }

    private fun settleIfPartial() {
        interruptedTarget?.let { resume ->
            interruptedTarget = null
            animateTo(resume)
            return
        }
        val current = offset
        val atRest = current == 0f || current == startWidth.toFloat() || current == -endWidth.toFloat()
        if (!atRest) settle(cancelled = true)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val logicalDx = (ev.x - downX) * dir
                val maxStart = if (fullSwipeAvailable) width.toFloat() else startWidth.toFloat()
                val target = (startOffset + logicalDx).coerceIn(-endWidth.toFloat(), maxStart)
                applyOffset(target)
                val armed = fullSwipeAvailable && target >= width * FULL_SWIPE_FRACTION
                if (armed != fullSwipeArmed) {
                    fullSwipeArmed = armed
                    if (armed) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                closeOnUp = false
                if (dragging) {
                    dragging = false
                    settle(cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL)
                } else {
                    settleIfPartial()
                }
                return true
            }
        }
        return true
    }

    private fun settle(cancelled: Boolean) {
        val current = offset
        val start = startWidth
        val end = endWidth
        when {
            !cancelled && fullSwipeArmed -> {
                fullSwipeArmed = false
                animateTo(0f)
                onFullSwipeStart?.invoke()
            }
            current > 0 && start > 0 && current >= start / 2f -> animateTo(start.toFloat())
            current < 0 && end > 0 && -current >= end / 2f -> animateTo(-end.toFloat())
            else -> animateTo(0f)
        }
    }

    /** Closes the row; `animate = false` also drops any in-flight gesture state (rebind). */
    fun close(animate: Boolean = true) {
        if (!::foreground.isInitialized) return
        if (animate) {
            // Already closed, or already closing (scroll listeners call this per frame): no restart.
            if ((animator == null && !isOpen) || animationTarget == 0f) return
            animateTo(0f)
        } else {
            cancelAnimation()
            interruptedTarget = null
            dragging = false
            closeOnUp = false
            fullSwipeArmed = false
            applyOffset(0f)
            callback?.onClosed(this)
        }
    }

    private fun cancelAnimation() {
        interruptedTarget = animationTarget
        animationTarget = null
        animator?.cancel()
        animator = null
    }

    private fun animateTo(target: Float) {
        cancelAnimation()
        interruptedTarget = null
        val from = offset
        if (from == target) {
            notifyState(target)
            return
        }
        animationTarget = target
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = SNAP_DURATION_MS
            addUpdateListener { applyOffset(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    // A cancelled animation left the row somewhere in between: no state change.
                    if (cancelled) return
                    animationTarget = null
                    animator = null
                    notifyState(target)
                }
            })
            start()
        }
    }

    private fun notifyState(target: Float) {
        if (target == 0f) callback?.onClosed(this) else callback?.onOpened(this)
    }

    /**
     * Pure render-thread properties: translation of the row, and the layout's own background in
     * the start-button colour once the reveal exceeds the button, so a full swipe reads as the
     * button stretching over the row without a layout pass per frame.
     */
    private fun applyOffset(logical: Float) {
        foreground.translationX = logical * dir
        val stretched = logical > startWidth
        val wanted = if (stretched) (startActions.background as? ColorDrawable)?.color ?: Color.TRANSPARENT else Color.TRANSPARENT
        if ((background as? ColorDrawable)?.color != wanted) setBackgroundColor(wanted)
    }

    private fun isOnRevealedAction(x: Float): Boolean {
        val current = offset
        val logicalX = if (dir < 0) width - x else x
        return (current > 0 && logicalX < current) || (current < 0 && logicalX > width + current)
    }

    companion object {
        private const val FULL_SWIPE_FRACTION = 0.6f
        private const val SNAP_DURATION_MS = 180L
    }
}
