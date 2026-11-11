package com.difft.android.chat.recent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.core.view.isVisible
import com.difft.android.chat.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt






















class ChatListSwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Callback {
        fun onOpened(layout: ChatListSwipeLayout)
        fun onClosed(layout: ChatListSwipeLayout)
    }

    
    var swipeEnabled: Boolean = true
        set(value) {
            field = value
            if (!value && ::foreground.isInitialized && isOpen) close(animate = false)
        }
    var callback: Callback? = null

    
    var onFullSwipeStart: (() -> Unit)? = null

    private lateinit var startActions: View
    private lateinit var endActions: View
    private lateinit var foreground: View

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var dragging = false
    private var closeOnUp = false
    private var fullSwipeArmed = false
    private var animator: ValueAnimator? = null

    
    private var animationTarget: Float? = null

    




    private var interruptedTarget: Float? = null

    
    private val dir: Float
        get() = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f

    
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
        get() = if (startActions.isVisible) startActions.measuredWidth else 0

    private val endWidth: Int
        get() = if (endActions.isVisible) endActions.measuredWidth else 0

    private val fullSwipeAvailable: Boolean
        get() = startActions.isVisible && onFullSwipeStart != null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimation()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                downX = ev.x
                downY = ev.y
                startOffset = offset
                dragging = false
                fullSwipeArmed = false
                
                
                closeOnUp = isOpen && !isOnRevealedAction(ev.x, ev.y)
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (startDragPastSlop(ev)) return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                
                velocityTracker?.recycle()
                velocityTracker = null
                val wasCloseTap = closeOnUp && !dragging && ev.actionMasked == MotionEvent.ACTION_UP
                closeOnUp = false
                if (wasCloseTap) {
                    close()
                    return true 
                }
                
                
                if (!dragging) settleIfPartial()
            }
        }
        return dragging
    }

    
    private fun startDragPastSlop(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (dragging || abs(dx) <= touchSlop || abs(dx) <= abs(dy)) return false
        dragging = true
        interruptedTarget = null 
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
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
                velocityTracker?.addMovement(ev)
                if (!dragging && !startDragPastSlop(ev)) return true
                val raw = startOffset + (ev.x - downX) * dir
                applyOffset(rubberBanded(raw))
                fullSwipeArmed = fullSwipeAvailable && raw >= width * FULL_SWIPE_FRACTION
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasCloseTap = closeOnUp && !dragging && ev.actionMasked == MotionEvent.ACTION_UP
                closeOnUp = false
                velocityTracker?.addMovement(ev)
                val velocity = velocityTracker?.run {
                    computeCurrentVelocity(1000)
                    xVelocity * dir
                } ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null
                if (dragging) {
                    dragging = false
                    settle(cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL, velocity = velocity)
                } else if (wasCloseTap) {
                    close()
                } else {
                    settleIfPartial()
                }
                return true
            }
        }
        return true
    }

    
    private fun rubberBanded(raw: Float): Float {
        val maxStart = startWidth.toFloat()
        val maxEnd = endWidth.toFloat()
        return when {
            raw > maxStart -> maxStart + (raw - maxStart) * RUBBER_BAND
            raw < -maxEnd -> -maxEnd + (raw + maxEnd) * RUBBER_BAND
            else -> raw
        }
    }

    
    private fun settle(cancelled: Boolean, velocity: Float = 0f) {
        val current = offset
        val projected = current + velocity * VELOCITY_PROJECTION_S
        val start = startWidth
        val end = endWidth
        when {
            !cancelled && fullSwipeArmed -> {
                fullSwipeArmed = false
                close(animate = false)
                onFullSwipeStart?.invoke()
            }
            current > 0 && start > 0 && projected > start / 2f -> animateTo(start.toFloat())
            current < 0 && end > 0 && -projected > end / 2f -> animateTo(-end.toFloat())
            else -> animateTo(0f)
        }
    }

    
    fun close(animate: Boolean = true) {
        if (!::foreground.isInitialized) return
        if (animate) {
            
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
            duration = SPRING_DURATION_MS
            interpolator = SPRING
            addUpdateListener { applyOffset(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    
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

    



    private fun applyOffset(logical: Float) {
        val wasOpen = isOpen
        foreground.translationX = logical * dir
        
        if (isOpen != wasOpen) invalidate()
        revealButtons(startActions, logical.coerceAtLeast(0f), fromStart = true)
        revealButtons(endActions, (-logical).coerceAtLeast(0f), fromStart = false)
    }

    



    private fun revealButtons(group: View, revealed: Float, fromStart: Boolean) {
        if (group !is ViewGroup || !group.isVisible) return
        group.children.filter { it.isVisible }.forEach { button ->
            
            val left = group.left + button.left
            val right = group.left + button.right
            val logicalStart = if (dir > 0) left else width - right
            val logicalEnd = if (dir > 0) right else width - left
            val fromEdge = if (fromStart) logicalStart else width - logicalEnd
            val progress = if (button.width > 0) ((revealed - fromEdge) / button.width).coerceIn(0f, 1f) else 1f
            button.scaleX = progress
            button.scaleY = progress
            button.alpha = REVEAL_MIN_ALPHA + (1f - REVEAL_MIN_ALPHA) * progress
        }
    }

    



    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean =
        if (child !== foreground && !isOpen) false else super.drawChild(canvas, child, drawingTime)

    




    private fun isOnRevealedAction(x: Float, y: Float): Boolean {
        val current = offset
        val logicalX = if (dir < 0) width - x else x
        val group = when {
            current > 0 && logicalX < current -> startActions
            current < 0 && logicalX > width + current -> endActions
            else -> return false
        }
        if (group !is ViewGroup || !group.isVisible) return false
        return group.children.any { button ->
            button.isVisible &&
                x >= group.left + button.left && x < group.left + button.right &&
                y >= group.top + button.top && y < group.top + button.bottom
        }
    }

    



    private class SpringInterpolator(private val damping: Float, private val stiffness: Float) : Interpolator {
        private val damped = stiffness * sqrt(1f - damping * damping)

        override fun getInterpolation(t: Float): Float {
            if (t >= 1f) return 1f
            val decay = exp(-damping * stiffness * t)
            return 1f - decay * (cos(damped * t) + damping * stiffness / damped * sin(damped * t))
        }
    }

    companion object {
        private const val FULL_SWIPE_FRACTION = 0.6f
        private const val RUBBER_BAND = 0.18f
        private const val VELOCITY_PROJECTION_S = 0.12f
        private const val REVEAL_MIN_ALPHA = 0.3f
        private const val SPRING_DURATION_MS = 360L
        private val SPRING: Interpolator = SpringInterpolator(damping = 0.86f, stiffness = 10f)
    }
}
