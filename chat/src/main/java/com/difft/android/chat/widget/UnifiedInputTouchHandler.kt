package com.difft.android.chat.widget

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs








class UnifiedInputTouchHandler(
    private val moveTolerancePx: Int,
    private val shouldIntercept: () -> Boolean,
    private val callbacks: Callbacks,
    private val longPressTimeoutMs: Long = LONG_PRESS_TIMEOUT_MS,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : View.OnTouchListener {

    interface Callbacks {
        fun onPressStateChanged(pressed: Boolean)
        fun onTap()

        
        fun onLongPress(): Boolean
        fun onHoldMove(rawX: Float, rawY: Float)
        fun onHoldEnd()
    }

    private enum class State { IDLE, PRESSED, HOLDING, ABANDONED }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f

    private val longPressRunnable = Runnable {
        if (state != State.PRESSED) return@Runnable
        callbacks.onPressStateChanged(false)
        state = if (callbacks.onLongPress()) State.HOLDING else State.ABANDONED
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            reset()
            if (!shouldIntercept()) return false
            v.parent?.requestDisallowInterceptTouchEvent(true)
            downX = event.rawX
            downY = event.rawY
            state = State.PRESSED
            callbacks.onPressStateChanged(true)
            handler.postDelayed(longPressRunnable, longPressTimeoutMs)
            return true
        }
        if (state == State.IDLE) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> when (state) {
                State.HOLDING -> callbacks.onHoldMove(event.rawX, event.rawY)
                State.PRESSED -> if (abs(event.rawX - downX) > moveTolerancePx || abs(event.rawY - downY) > moveTolerancePx) {
                    handler.removeCallbacks(longPressRunnable)
                    callbacks.onPressStateChanged(false)
                    state = State.ABANDONED
                }
                else -> Unit
            }

            MotionEvent.ACTION_UP -> {
                val previous = state
                reset()
                when (previous) {
                    State.HOLDING -> callbacks.onHoldEnd()
                    State.PRESSED -> {
                        callbacks.onPressStateChanged(false)
                        if (event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()) callbacks.onTap()
                    }
                    else -> Unit
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                val previous = state
                reset()
                when (previous) {
                    
                    State.HOLDING -> callbacks.onHoldEnd()
                    State.PRESSED -> callbacks.onPressStateChanged(false)
                    else -> Unit
                }
            }
        }
        return true
    }

    private fun reset() {
        handler.removeCallbacks(longPressRunnable)
        state = State.IDLE
    }

    companion object {
        const val LONG_PRESS_TIMEOUT_MS = 350L

        
        const val MOVE_TOLERANCE_DP = 10f
    }
}
