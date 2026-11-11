package com.difft.android.call.ui.actionbar

import com.difft.android.base.utils.cutoutRectsInWindow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.difft.android.call.LCallUiConstants


internal data class RailCutoutClearance(val topDp: Float, val bottomDp: Float)







internal fun railCutoutClearance(
    cutouts: List<Rect>,
    windowWidthDp: Float,
    windowHeightDp: Float,
    railWidthDp: Float,
    isRtl: Boolean,
): RailCutoutClearance {
    val bandLeft = if (isRtl) 0f else windowWidthDp - railWidthDp
    val bandRight = if (isRtl) railWidthDp else windowWidthDp
    var top = 0f
    var bottom = 0f
    cutouts.forEach { r ->
        if (r.isEmpty || r.right <= bandLeft || r.left >= bandRight) return@forEach
        if (r.bottom <= 0f || r.top >= windowHeightDp) return@forEach
        if (r.center.y < windowHeightDp / 2) {
            top = maxOf(top, r.bottom.coerceAtMost(windowHeightDp))
        } else {
            bottom = maxOf(bottom, (windowHeightDp - r.top).coerceAtMost(windowHeightDp))
        }
    }
    return RailCutoutClearance(top, bottom)
}






internal fun cutoutClearedTopDp(
    cutouts: List<Rect>,
    left: Float,
    right: Float,
    windowHeightDp: Float,
    baseTopDp: Float,
    heightDp: Float,
): Float {
    var top = baseTopDp
    cutouts.filter { !it.isEmpty && it.center.y < windowHeightDp / 2 }.sortedBy { it.top }.forEach { r ->
        if (r.right <= left || r.left >= right) return@forEach
        if (r.top < top + heightDp && r.bottom > top) top = r.bottom
    }
    return top
}


internal const val TITLE_START_SLOT_DP = 62f










internal fun titleRowTopDp(
    cutouts: List<Rect>,
    windowWidthDp: Float,
    windowHeightDp: Float,
    baseTopDp: Float,
    rail: SideRail? = null,
    alignWithRail: Boolean = false,
    minimizeAside: Boolean = false,
): Float {
    val edge = rail?.widthDp?.toFloat() ?: if (minimizeAside) TITLE_START_SLOT_DP else 0f
    val cleared = cutoutClearedTopDp(
        cutouts, edge, windowWidthDp - edge, windowHeightDp, baseTopDp, LCallUiConstants.TOP_BAR_HEIGHT_DP.toFloat(),
    )
    if (rail == null || !alignWithRail) return cleared
    return maxOf(cleared, rail.topInset.value - LCallUiConstants.TOP_BAR_MARGIN_TOP_DP)
}





internal fun minimizeStartDp(
    cutouts: List<Rect>,
    windowWidthDp: Float,
    rowTopDp: Float,
    basePaddingDp: Float,
    isRtl: Boolean,
): Float {
    val rowBottom = rowTopDp + LCallUiConstants.TOP_BAR_HEIGHT_DP
    var start = basePaddingDp
    cutouts.forEach { r ->
        if (r.isEmpty || r.top >= rowBottom || r.bottom <= rowTopDp) return@forEach
        val near = if (isRtl) windowWidthDp - r.right else r.left
        val far = if (isRtl) windowWidthDp - r.left else r.right
        if (near < TITLE_START_SLOT_DP && far > 0f) start = maxOf(start, far)
    }
    return start
}


@Composable
internal fun rememberMinimizeStart(rowTop: Dp, basePadding: Dp): Dp {
    val window = rememberCallWindowSizeDp()
    val cutouts = rememberDisplayCutoutRectsDp()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember(window, cutouts, rowTop, basePadding, isRtl) {
        minimizeStartDp(cutouts, window.width, rowTop.value, basePadding.value, isRtl).dp
    }
}





@Composable
internal fun rememberTitleRowTop(baseTop: Dp, alignWithRail: Boolean, minimizeAside: Boolean = false): Dp {
    val window = rememberCallWindowSizeDp()
    val cutouts = rememberDisplayCutoutRectsDp()
    val rail = rememberCallSideRail()
    return remember(window, cutouts, rail, baseTop, alignWithRail, minimizeAside) {
        titleRowTopDp(cutouts, window.width, window.height, baseTop.value, rail, alignWithRail, minimizeAside).dp
    }
}





@Composable
internal fun rememberTitleRowTopOutsideShare(): Dp =
    rememberTitleRowTop(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), alignWithRail = true)







@Composable
internal fun rememberDisplayCutoutRectsDp(): List<Rect> {
    val view = LocalView.current
    val density = LocalDensity.current
    val cutout = WindowInsets.displayCutout
    val left = cutout.getLeft(density, LayoutDirection.Ltr)
    val top = cutout.getTop(density)
    val right = cutout.getRight(density, LayoutDirection.Ltr)
    val bottom = cutout.getBottom(density)
    
    val window = rememberCallWindowSizeDp()
    
    val statusTop = WindowInsets.statusBars.getTop(density)
    return remember(view, density, left, top, right, bottom, window, statusTop) {
        val scale = density.density
        view.cutoutRectsInWindow().map {
            Rect(it.left / scale, it.top / scale, it.right / scale, it.bottom / scale)
        }
    }
}
