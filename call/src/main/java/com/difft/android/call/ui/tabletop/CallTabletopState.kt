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

/** The call activity's fold reports; null (previews, tests, other hosts) keeps tabletop off. */
internal val LocalCallFoldReports = staticCompositionLocalOf<StateFlow<CallFoldReport?>?> { null }

/** The tabletop geometry drawn from the layout plan ([planTabletop]); null outside the tabletop posture. */
internal val LocalCallTabletop = compositionLocalOf<TabletopGeometry?> { null }

private val NoFoldReports = MutableStateFlow<CallFoldReport?>(null)

/**
 * The half-open fold for the layout (design §4.5): the fold report resolved against the call root,
 * then debounced by [TabletopGate] (600ms to enter, 300ms flat hysteresis), tabletop and book alike.
 * While in picture in picture the gate is not fed (the small window would lose the fold), so it
 * keeps its fold and reports it unchanged on return. A fold decided for another root size is never
 * returned, even for the frame before the gate sees the resize.
 */
@Composable
internal fun rememberGatedFold(controller: CallUiController): TabletopGeometry? {
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
    // Frozen while in picture in picture, and after it until the root has left the picture-in-picture
    // size (the flag, the resize and the fold report arrive in any order).
    val fed = remember { FedResolution() }
    val frozen = fed.frozen(isInPip, size.width, size.height)
    if (!frozen) fed.value = resolution

    val activity = LocalContext.current.findActivity()
    val logger = remember { TabletopLogger() }
    var gate by remember { mutableStateOf<GateState>(GateState.Initial) }
    // Both a fold report and a measured root are needed before a NoFold means "no fold": an
    // unmeasured root (size 0) also resolves to NoFold and would otherwise send a call that opens
    // already standing through the enter debounce.
    val ready = report != null && size.width > 0 && size.height > 0
    val input = fed.value
    // Restarts only on a different resolution, which cancels a running timer. Keyed on readiness
    // too: an empty first report resolves to the same NoFold as "not ready" and must still move the
    // gate out of Initial.
    LaunchedEffect(input, ready) {
        if (input == null) return@LaunchedEffect
        // Not ready yet: keep Initial so the first real fold can enter at once.
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
    // While frozen the window is not the one the fold was decided for; keep it anyway.
    return if (frozen) geometry else geometry?.takeIf { it.widthPx == size.width && it.heightPx == size.height }
}

/**
 * Whether a screen share keeps the portrait lock for a half-open fold (design decision 38): entered
 * on the debounced fold the layout gets ([reported]), held while the raw report stays half open. The
 * rotation that lock causes gives the fold a new geometry, which the gate waits out for 600ms with
 * no fold; without the hold the share would turn landscape again for that wait, and back, in a loop.
 */
internal fun shareHalfOpen(previous: Boolean, reported: Boolean, rawHalfOpen: Boolean): Boolean =
    reported || (previous && rawHalfOpen)

/** [shareHalfOpen] over the call's fold reports; written in composition only. */
@Composable
internal fun rememberShareHalfOpen(reported: Boolean): Boolean {
    val report by (LocalCallFoldReports.current ?: NoFoldReports).collectAsState()
    val rawHalfOpen = report?.folds?.any { it.state == CallFoldState.HALF_OPENED } == true
    val held = remember { booleanArrayOf(false) }
    held[0] = shareHalfOpen(held[0], reported, rawHalfOpen)
    return held[0]
}

/**
 * The dock region below the fold: a bottom-anchored band [TabletopGeometry.dockHeightDp] tall, the
 * same box the dock's controls sit in, for overlays that replace the console while open.
 */
internal fun Modifier.tabletopDockRegion(tabletop: TabletopGeometry): Modifier =
    fillMaxSize().wrapContentHeight(Alignment.Bottom, unbounded = false).height(tabletop.dockHeightDp.dp)

/**
 * The scrim of the call's bottom sheets: none while the tabletop dock is shown, so the stage above
 * the fold stays as bright as it is with the sheet closed; the shared scrim otherwise.
 */
@Composable
internal fun callSheetScrimColor(): Color =
    if (LocalCallTabletop.current != null) Color.Transparent else DifftBottomSheetDefaults.ScrimColor

/** The navigation bar's bottom inset in dp (0 while the call hides it); the dock console stays above it. */
@Composable
internal fun rememberTabletopBottomInsetDp(): Float {
    val density = LocalDensity.current
    return WindowInsets.navigationBars.getBottom(density) / density.density
}

/** The gate's input, kept while picture in picture freezes it; written in composition only. */
internal class FedResolution {
    var value: TabletopResolution? = null

    /** The fold the gate holds. */
    var kept: TabletopGeometry? = null
    private var pipSize: Pair<Int, Int>? = null

    /**
     * Whether the gate must not be fed now: in picture in picture, and after it while the root is
     * still the picture-in-picture size (the flag can clear before the window grows back), unless
     * it is already [kept]'s size. Written in composition only.
     */
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
