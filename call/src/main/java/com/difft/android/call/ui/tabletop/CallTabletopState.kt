package com.difft.android.call.ui.tabletop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.DifftBottomSheetDefaults
import com.difft.android.call.core.CallUiController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


internal val LocalCallFoldReports = staticCompositionLocalOf<StateFlow<CallFoldReport?>?> { null }


internal val LocalCallTabletop = compositionLocalOf<TabletopGeometry?> { null }

private val NoFoldReports = MutableStateFlow<CallFoldReport?>(null)









@Composable
internal fun rememberGatedFold(controller: CallUiController, settled: MutableState<Boolean>? = null): TabletopGeometry? {
    val report by (LocalCallFoldReports.current ?: NoFoldReports).collectAsState()
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val location = IntArray(2).also { LocalView.current.getLocationInWindow(it) }
    val left = location[0]
    val top = location[1]
    val isInPip by controller.isInPipMode.collectAsState()
    val resolution = remember(report, left, top, size, density) {
        resolveTabletop(report, left, top, size.width, size.height, density)
    }
    
    
    val fed = remember { FedResolution() }
    val frozen = fed.frozen(isInPip, size.width, size.height)
    if (!frozen) fed.value = resolution

    val activity = LocalContext.current.findActivity()
    val logger = remember { TabletopLogger() }
    var gate by remember { mutableStateOf<GateState>(GateState.Initial) }
    
    
    
    val ready = report != null && size.width > 0 && size.height > 0
    val input = fed.value
    
    
    
    LaunchedEffect(input, ready) {
        if (input == null) return@LaunchedEffect
        
        if (gate == GateState.Initial && !ready) return@LaunchedEffect
        val multiWindow = { activity?.isInMultiWindowMode == true }
        var step = TabletopGate.onInput(gate, input)
        gate = step.state
        logger.onStep(step, multiWindow)
        while (true) {
            val timer = step.timerMs ?: break
            delay(timer)
            step = TabletopGate.onTimeout(gate)
            gate = step.state
            logger.onStep(step, multiWindow)
        }
    }
    val geometry = gate.geometry
    fed.kept = geometry
    val decided = gate != GateState.Initial
    SideEffect { if (settled != null && settled.value != decided) settled.value = decided }
    
    return if (frozen) geometry else geometry?.takeIf { it.widthPx == size.width && it.heightPx == size.height }
}







internal fun shareHalfOpen(previous: Boolean, reported: Boolean, rawHalfOpen: Boolean): Boolean =
    reported || (previous && rawHalfOpen)


@Composable
internal fun rememberShareHalfOpen(reported: Boolean): Boolean {
    val report by (LocalCallFoldReports.current ?: NoFoldReports).collectAsState()
    val rawHalfOpen = report?.folds?.any { it.state == CallFoldState.HALF_OPENED } == true
    val held = remember { booleanArrayOf(false) }
    held[0] = shareHalfOpen(held[0], reported, rawHalfOpen)
    return held[0]
}





internal fun Modifier.tabletopDockRegion(tabletop: TabletopGeometry): Modifier =
    fillMaxSize().wrapContentHeight(Alignment.Bottom, unbounded = false).height(tabletop.dockHeightDp.dp)





@Composable
internal fun callSheetScrimColor(): Color =
    if (LocalCallTabletop.current != null) Color.Transparent else DifftBottomSheetDefaults.ScrimColor


@Composable
internal fun rememberTabletopBottomInsetDp(): Float {
    val density = LocalDensity.current
    return WindowInsets.navigationBars.getBottom(density) / density.density
}


internal class FedResolution {
    var value: TabletopResolution? = null

    
    var kept: TabletopGeometry? = null
    private var pipSize: Pair<Int, Int>? = null

    




    fun frozen(inPip: Boolean, widthPx: Int, heightPx: Int): Boolean {
        if (inPip) {
            pipSize = widthPx to heightPx
            return true
        }
        val pip = pipSize ?: return false
        val k = kept
        val back = (widthPx to heightPx) != pip || (k != null && k.widthPx == widthPx && k.heightPx == heightPx)
        if (back) pipSize = null
        return !back
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
