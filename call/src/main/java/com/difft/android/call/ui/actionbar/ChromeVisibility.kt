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


const val CHROME_FADE_MS = 200


const val CHROME_RESERVE_ANIMATION_MS = 250




fun CallUiController.panelOpenFlow(): Flow<Boolean> = combine(
    showToolBarBottomViewEnable,
    showSimpleBarrageEnabled,
    showBottomCallEndViewEnable,
) { more, emoji, end -> more || emoji || end }


fun CallUiController.isAnyPanelOpen(): Boolean =
    showToolBarBottomViewEnable.value || showSimpleBarrageEnabled.value || showBottomCallEndViewEnable.value









fun CallUiController.chromeVisibleFlow(toggle: StateFlow<Boolean>, onRail: Boolean): Flow<Boolean> =
    combine(toggle, panelOpenFlow(), isInPipMode) { shown, panel, pip -> pip || (shown && (onRail || !panel)) }
        .distinctUntilChanged()







@Composable
fun rememberChromeVisible(controller: CallUiController, toggle: StateFlow<Boolean>): State<Boolean> {
    val onRail = rememberKeepsChromeUnderPanels()
    val flow = remember(controller, toggle, onRail) { controller.chromeVisibleFlow(toggle, onRail) }
    val initial = remember(controller, toggle, onRail) { chromeVisibleNow(controller, toggle, onRail) }
    return flow.collectAsState(initial = initial)
}





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






private fun chromeVisibleNow(c: CallUiController, toggle: StateFlow<Boolean>, onRail: Boolean): Boolean =
    c.isInPipMode.value || (toggle.value && (onRail || !c.isAnyPanelOpen()))







@Composable
fun rememberChromeAlpha(controller: CallUiController, toggle: StateFlow<Boolean>): Animatable<Float, AnimationVector1D> {
    val onRail = rememberKeepsChromeUnderPanels()
    val alpha = remember(controller, toggle) {
        Animatable(if (chromeVisibleNow(controller, toggle, onRail)) 1f else 0f)
    }
    val entered = remember(controller, toggle) { BooleanArray(1) }
    LaunchedEffect(controller, toggle, onRail) {
        
        
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
