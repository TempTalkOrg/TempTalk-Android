package com.difft.android.call.util

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent











class IncomingCallTouchGuard(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private var shownAt: Long = clock()
    private var lastTouch: String = "none"

    
    fun markShown() {
        shownAt = clock()
    }

    
    fun sinceShownMs(): Long = clock() - shownAt

    
    fun allowsTap(): Boolean = sinceShownMs() >= cooldownMs

    



    fun recordTouch(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val touchscreen = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        lastTouch = "src=0x${Integer.toHexString(event.source)} touchscreen=$touchscreen" +
            " tool=${event.getToolType(0)} x=${event.x.toInt()} y=${event.y.toInt()}" +
            " size=${"%.2f".format(event.size)} pressure=${"%.2f".format(event.pressure)}" +
            " sinceShownMs=${event.eventTime - shownAt}"
    }

    
    fun describeLastTouch(): String = lastTouch

    companion object {
        const val DEFAULT_COOLDOWN_MS = 500L
    }
}
