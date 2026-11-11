package com.difft.android.call.ui.layout

import android.app.Activity
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.ui.screenshare.getActivity
import com.difft.android.call.layout.Size as LayoutSize





internal val LocalCallScreenCorners = compositionLocalOf<CallScreenCorners?> { null }


internal data class ScreenCornerRead(val radii: CornerRadii?, val wholeScreen: Boolean) {
    
    fun cornersIn(window: LayoutSize): CallScreenCorners? = callScreenCorners(radii, wholeScreen, window)
}








@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberCallScreenCorners(): CallScreenCorners? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val view = LocalView.current
    val activity = LocalContext.current.getActivity()
    val density = LocalDensity.current
    
    
    val containerPx = LocalWindowInfo.current.containerSize
    val decorPx = rememberLaidOutSize(view.rootView)
    val configuration = LocalConfiguration.current
    val insetsKey = insetsKey(density, WindowInsets.displayCutout, WindowInsets.statusBarsIgnoringVisibility, WindowInsets.navigationBarsIgnoringVisibility)
    val read = remember(view, activity, density, containerPx, decorPx, configuration, insetsKey) {
        readScreenCorners(view, activity, density.density, decorPx)
    }
    LaunchedEffect(read) {
        val line = formatScreenCornersLog(read)
        L.i { line }
    }
    return read.cornersIn(LayoutSize(containerPx.width / density.density.toDouble(), containerPx.height / density.density.toDouble()))
}


@Composable
private fun rememberLaidOutSize(view: View): IntSize {
    val size = remember(view) { mutableStateOf(IntSize(view.width, view.height)) }
    DisposableEffect(view) {
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> size.value = IntSize(v.width, v.height) }
        view.addOnLayoutChangeListener(listener)
        size.value = IntSize(view.width, view.height)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    return size.value
}


private fun insetsKey(density: Density, vararg insets: WindowInsets): List<Int> = insets.flatMap {
    listOf(it.getLeft(density, LayoutDirection.Ltr), it.getTop(density), it.getRight(density, LayoutDirection.Ltr), it.getBottom(density))
}






@RequiresApi(Build.VERSION_CODES.S)
private fun readScreenCorners(view: View, activity: Activity?, density: Float, decorPx: IntSize): ScreenCornerRead {
    val insets = view.rootWindowInsets
    val px = CORNER_POSITIONS.map { insets?.getRoundedCorner(it)?.radius }
    val radii = roundedCornerRadii(px, density.toDouble())
    val whole = activity?.let {
        val current = it.windowManager.currentWindowMetrics.bounds
        val maximum = it.windowManager.maximumWindowMetrics.bounds
        isWholeScreen(
            current = IntRect(current.left, current.top, current.right, current.bottom),
            maximum = IntRect(maximum.left, maximum.top, maximum.right, maximum.bottom),
            multiWindow = it.isInMultiWindowMode,
            view = decorPx,
        )
    } ?: false
    return ScreenCornerRead(radii, whole)
}


@RequiresApi(Build.VERSION_CODES.S)
private val CORNER_POSITIONS = listOf(
    RoundedCorner.POSITION_TOP_LEFT,
    RoundedCorner.POSITION_TOP_RIGHT,
    RoundedCorner.POSITION_BOTTOM_RIGHT,
    RoundedCorner.POSITION_BOTTOM_LEFT,
)


internal fun formatScreenCornersLog(read: ScreenCornerRead): String {
    val dp = read.radii?.let { r ->
        listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft).joinToString(",") { "%.1f".format(java.util.Locale.US, it) }
    } ?: "none"
    return "[Call] screen corners whole=${read.wholeScreen} dp=$dp"
}
