package com.difft.android.call.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Tap interceptor for the call screen. Fires [onTap] only on a clean tap —
 * no long-press, no drag.
 *
 * Intercepts pointer events on [PointerEventPass.Initial] to prevent them
 * from bubbling to the underlying `CallSurface.clickable`, which avoids
 * double-toggling when hidden top/bottom bars or the barrage menu outer
 * ring need to re-show overlays on their own taps.
 *
 * `Modifier.clickable` / `detectTapGestures` don't work here:
 * - `clickable` has no slop / long-press threshold customization.
 * - `detectTapGestures` runs on Main pass and doesn't consume the down
 *   event, so it can't stop bubbling.
 *
 * @param enabled when false, returns the receiver unchanged so the inner
 *                `pointerInput` coroutine is torn down and rebuilt cleanly
 *                on flip. Equivalent to the call-site idiom
 *                `if (cond) Modifier.pointerInput(...) else Modifier`.
 * @param onTap   invoked on clean tap only. Long-press, drag and
 *                multi-touch gestures never invoke it.
 */
fun Modifier.tapInterceptor(
    enabled: Boolean = true,
    onTap: () -> Unit,
): Modifier = if (!enabled) this else this.then(
    Modifier.pointerInput(Unit) {
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                val downEvent = awaitPointerEvent(PointerEventPass.Initial)
                val down = downEvent.changes.firstOrNull { it.pressed } ?: continue
                down.consume()
                val downPos = down.position
                val downTime = down.uptimeMillis
                var moved = false
                var released = false
                while (!released) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    e.changes.forEach { change ->
                        change.consume()
                        if (!moved &&
                            (change.position - downPos).getDistance() > slop
                        ) {
                            moved = true
                        }
                        if (!change.pressed && change.id == down.id) {
                            released = true
                            val isTap = !moved &&
                                (change.uptimeMillis - downTime) < longPressTimeout
                            if (isTap) onTap()
                        }
                    }
                }
            }
        }
    }
)
