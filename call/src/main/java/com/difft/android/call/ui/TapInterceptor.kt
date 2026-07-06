package com.difft.android.call.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Tap interceptor for the call screen. Fires [onTap] only on a clean,
 * single-finger tap — no long-press, no drag, no multi-touch.
 *
 * Observes pointer events on [PointerEventPass.Initial] and consumes ONLY the
 * up-event of a confirmed clean tap, which stops that tap from bubbling to the
 * underlying `CallSurface.clickable` (avoids double-toggling when hidden
 * top/bottom bars or the barrage menu outer ring re-show overlays on their own
 * taps).
 *
 * Crucially, it does NOT consume the down-event, nor any event of a multi-touch
 * gesture. The hidden top-bar overlay fills the whole screen (Material `Surface`
 * propagates min-constraints to its children), so consuming every pointer
 * change here used to swallow the two-finger pinch and block the screen-share
 * `detectTransformGestures` zoom whenever the control bars were auto-hidden.
 * Deferring consumption to a confirmed single-finger tap lets pinch-zoom and
 * single-finger pan pass through to the video renderer untouched.
 *
 * `Modifier.clickable` / `detectTapGestures` don't work here:
 * - `clickable` has no slop / long-press threshold customization.
 * - `detectTapGestures` runs on Main pass and can't stop bubbling on Initial.
 *
 * @param enabled when false, returns the receiver unchanged so the inner
 *                `pointerInput` coroutine is torn down and rebuilt cleanly
 *                on flip. Equivalent to the call-site idiom
 *                `if (cond) Modifier.pointerInput(...) else Modifier`.
 * @param onTap   invoked on clean single-finger tap only. Long-press, drag and
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
                // 第一根手指按下：仅观察，不消费。这样多指手势（如屏幕共享画面
                // 的双指缩放）以及单指拖动仍能下传到底层 VideoRenderer 的
                // detectTransformGestures，不会被本拦截器吞掉。
                val downEvent = awaitPointerEvent(PointerEventPass.Initial)
                val down = downEvent.changes.firstOrNull { it.pressed } ?: continue
                val downPos = down.position
                val downTime = down.uptimeMillis
                var moved = false
                var multiTouch = downEvent.changes.count { it.pressed } > 1
                var primaryUp: PointerInputChange? = null
                // 持续观察直到「所有」手指都抬起，而不是主手指一抬起就退出。否则
                // 双指缩放时若主手指（down.id）先抬起，外层 while 会立刻把仍按住
                // 的第二根手指当成一次全新的按下（此时 multiTouch=false），待其
                // 抬起又被误判为单指点击，在缩放结束瞬间误触发 toggleOverlays。
                do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    // 出现第二根手指 → 判定为缩放/多指手势，绝不当作 tap，且全程
                    // 不消费，交由底层手势处理。
                    if (e.changes.count { it.pressed } > 1) {
                        multiTouch = true
                    }
                    e.changes.forEach { change ->
                        // 仅跟踪主手指（down.id）的位移，避免第二根手指的移动在
                        // multiTouch 确认前的同一帧里把 moved 误置为 true。
                        if (!moved && change.id == down.id &&
                            (change.position - downPos).getDistance() > slop
                        ) {
                            moved = true
                        }
                        // 仅记录主手指的抬起用于 tap 计时；循环本身会等所有手指抬起。
                        if (!change.pressed && change.id == down.id && primaryUp == null) {
                            primaryUp = change
                        }
                    }
                } while (e.changes.any { it.pressed })
                val up = primaryUp
                val isTap = !multiTouch && !moved && up != null &&
                    (up.uptimeMillis - downTime) < longPressTimeout
                if (isTap) {
                    // 仅在确认为「单指干净点击」时消费抬起事件，阻止其冒泡到
                    // CallSurface.clickable，避免重复 toggle。
                    up.consume()
                    onTap()
                }
            }
        }
    }
)
