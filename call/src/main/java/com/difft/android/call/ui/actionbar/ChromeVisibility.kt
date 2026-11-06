package com.difft.android.call.ui.actionbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.difft.android.call.core.CallUiController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Chrome (top bar + action bar) show / hide fade, per the design's 200ms opacity. */
const val CHROME_FADE_MS = 200

/** Content that reclaims space a hidden chrome frees (1v1 floating window, wide grid on the rail) eases over this. */
const val CHROME_RESERVE_ANIMATION_MS = 250

/**
 * Whether any floating panel is open. While one is, the design fades the whole bar-mode chrome
 * out and brings it back when the panel closes, without touching the user's show / hide toggle.
 */
fun CallUiController.panelOpenFlow(): Flow<Boolean> = combine(
    showToolBarBottomViewEnable,
    showSimpleBarrageEnabled,
    showBottomCallEndViewEnable,
) { more, emoji, end -> more || emoji || end }

/** Snapshot counterpart of [panelOpenFlow] for non-composable callers (timers, seeds). */
fun CallUiController.isAnyPanelOpen(): Boolean =
    showToolBarBottomViewEnable.value || showSimpleBarrageEnabled.value || showBottomCallEndViewEnable.value

/**
 * `toggle && !panelOpen`, de-duplicated — except in Picture-in-Picture, where the chrome that
 * survives (the title bar with name / duration) is the whole tile and must always show: the
 * sheets hide on PiP entry without clearing their flags, and a user may have hidden the chrome
 * before shrinking the window. [onRail]: beside the side rail (#1451) or on the tabletop dock an
 * open panel no longer fades the chrome, so only the toggle (a tap, the screen-share auto-hide)
 * hides it there.
 */
fun CallUiController.chromeVisibleFlow(toggle: StateFlow<Boolean>, onRail: Boolean): Flow<Boolean> =
    combine(toggle, panelOpenFlow(), isInPipMode) { shown, panel, pip -> pip || (shown && (onRail || !panel)) }
        .distinctUntilChanged()

/**
 * Composition-time visibility of one chrome piece. Drives the hidden-state tap interceptor
 * (a Boolean the modifier chain needs at composition), so a toggle recomposes the caller —
 * acceptable for the bars, whose child parameters are stable and skip. Follows the current
 * rail / bar mode, re-subscribing when a resize switches it.
 */
@Composable
fun rememberChromeVisible(controller: CallUiController, toggle: StateFlow<Boolean>): State<Boolean> {
    val onRail = rememberKeepsChromeUnderPanels()
    val flow = remember(controller, toggle, onRail) { controller.chromeVisibleFlow(toggle, onRail) }
    val initial = remember(controller, toggle, onRail) { chromeVisibleNow(controller, toggle, onRail) }
    return flow.collectAsState(initial = initial)
}

/**
 * [rememberChromeVisible] for two chrome pieces as one state, so a tap that flips both is seen in
 * a single composition and a reserve derived from both retargets once (#1451).
 */
@Composable
fun rememberChromeVisible(
    controller: CallUiController,
    first: StateFlow<Boolean>,
    second: StateFlow<Boolean>,
): State<Pair<Boolean, Boolean>> {
    val onRail = rememberKeepsChromeUnderPanels()
    val flow = remember(controller, first, second, onRail) {
        combine(controller.chromeVisibleFlow(first, onRail), controller.chromeVisibleFlow(second, onRail)) { a, b -> a to b }
    }
    val initial = remember(controller, first, second, onRail) {
        chromeVisibleNow(controller, first, onRail) to chromeVisibleNow(controller, second, onRail)
    }
    return flow.collectAsState(initial = initial)
}

/**
 * Snapshot of the visibility for a composable's first frame. Plain (non-composable) on purpose:
 * `StateFlow.value` must not be read in composition, and these reads are one-shot seeds that the
 * collectors above immediately supersede.
 */
private fun chromeVisibleNow(c: CallUiController, toggle: StateFlow<Boolean>, onRail: Boolean): Boolean =
    c.isInPipMode.value || (toggle.value && (onRail || !c.isAnyPanelOpen()))

/**
 * Animated opacity for one chrome piece. Read [Animatable.value] only inside `graphicsLayer`
 * (or another draw / layout lambda) so the fade never recomposes the subtree it dims.
 * The first emission snaps so entering a screen does not animate; a rail / bar switch afterwards
 * re-subscribes and fades to the new mode's visibility.
 */
@Composable
fun rememberChromeAlpha(controller: CallUiController, toggle: StateFlow<Boolean>): Animatable<Float, AnimationVector1D> {
    val onRail = rememberKeepsChromeUnderPanels()
    val alpha = remember(controller, toggle) {
        Animatable(if (chromeVisibleNow(controller, toggle, onRail)) 1f else 0f)
    }
    val entered = remember(controller, toggle) { BooleanArray(1) }
    LaunchedEffect(controller, toggle, onRail) {
        // collectLatest: animateTo suspends for the whole fade, and a serialized collector
        // would queue a reversal and play wrong-direction movement first.
        controller.chromeVisibleFlow(toggle, onRail).collectLatest { visible ->
            val target = if (visible) 1f else 0f
            if (!entered[0]) {
                entered[0] = true
                alpha.snapTo(target)
            } else {
                alpha.animateTo(target, tween(CHROME_FADE_MS))
            }
        }
    }
    return alpha
}
