package com.difft.android.call.util

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Rejects accept / reject taps that arrive before the incoming-call screen has been on screen long
 * enough for a person to have read it. A full-screen intent turns the display on with the buttons
 * already live under whatever is touching the glass, so a tap in the first few hundred milliseconds
 * is far more likely to be fabric or a palm than a decision.
 *
 * Also keeps the last pointer-down seen on those buttons so the accept/reject log line can say where
 * the tap came from (touchscreen vs. other input, tool type, position) — a click listener alone
 * cannot tell a pocket press from a finger.
 */
class IncomingCallTouchGuard(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    // uptimeMillis is the MotionEvent time base, so sinceShownMs in the touch log is comparable.
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private var shownAt: Long = clock()
    private var lastTouch: String = "none"

    /** Restarts the cool-down; call when the ring screen (re)appears. */
    fun markShown() {
        shownAt = clock()
    }

    /** Milliseconds since [markShown] (or construction). */
    fun sinceShownMs(): Long = clock() - shownAt

    /** True once the cool-down has elapsed and a tap may be acted on. */
    fun allowsTap(): Boolean = sinceShownMs() >= cooldownMs

    /**
     * Records the pointer-down of a touch stream on the answer controls. Feed every event from a
     * pass-through observer (the shells forward from dispatchTouchEvent) so handling is untouched.
     */
    fun recordTouch(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val touchscreen = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        lastTouch = "src=0x${Integer.toHexString(event.source)} touchscreen=$touchscreen" +
            " tool=${event.getToolType(0)} x=${event.x.toInt()} y=${event.y.toInt()}" +
            " size=${"%.2f".format(event.size)} pressure=${"%.2f".format(event.pressure)}" +
            " sinceShownMs=${event.eventTime - shownAt}"
    }

    /** Provenance of the most recent pointer-down, for the accept/reject log line. */
    fun describeLastTouch(): String = lastTouch

    companion object {
        const val DEFAULT_COOLDOWN_MS = 500L
    }
}
