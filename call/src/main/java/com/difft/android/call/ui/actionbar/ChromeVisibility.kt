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

/**
 * Whether any floating panel is open. While one is, the design fades the whole chrome out
 * and brings it back when the panel closes, without touching the user's show / hide toggle.
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
 * before shrinking the window.
 */
fun CallUiController.chromeVisibleFlow(toggle: StateFlow<Boolean>): Flow<Boolean> =
    combine(toggle, panelOpenFlow(), isInPipMode) { shown, panel, pip -> pip || (shown && !panel) }
        .distinctUntilChanged()

/**
 * Composition-time visibility of one chrome piece. Drives the hidden-state tap interceptor
 * (a Boolean the modifier chain needs at composition), so a toggle recomposes the caller —
 * acceptable for the bars, whose child parameters are stable and skip.
 */
@Composable
fun rememberChromeVisible(controller: CallUiController, toggle: StateFlow<Boolean>): State<Boolean> {
    val flow = remember(controller, toggle) { controller.chromeVisibleFlow(toggle) }
    val initial = remember(controller, toggle) { chromeVisibleNow(controller, toggle) }
    return flow.collectAsState(initial = initial)
}

/**
 * Snapshot of the visibility for a composable's first frame. Plain (non-composable) on purpose:
 * `StateFlow.value` must not be read in composition, and these reads are one-shot seeds that the
 * collectors above immediately supersede.
 */
private fun chromeVisibleNow(c: CallUiController, toggle: StateFlow<Boolean>): Boolean =
    c.isInPipMode.value || (toggle.value && !c.isAnyPanelOpen())

/**
 * Animated opacity for one chrome piece. Read [Animatable.value] only inside `graphicsLayer`
 * (or another draw / layout lambda) so the fade never recomposes the subtree it dims.
 * The first emission snaps so entering a screen does not animate.
 */
@Composable
fun rememberChromeAlpha(controller: CallUiController, toggle: StateFlow<Boolean>): Animatable<Float, AnimationVector1D> {
    val alpha = remember(controller, toggle) {
        Animatable(if (chromeVisibleNow(controller, toggle)) 1f else 0f)
    }
    LaunchedEffect(controller, toggle) {
        var first = true
        // collectLatest: animateTo suspends for the whole fade, and a serialized collector
        // would queue a reversal and play wrong-direction movement first.
        controller.chromeVisibleFlow(toggle).collectLatest { visible ->
            val target = if (visible) 1f else 0f
            if (first) {
                first = false
                alpha.snapTo(target)
            } else {
                alpha.animateTo(target, tween(CHROME_FADE_MS))
            }
        }
    }
    return alpha
}
