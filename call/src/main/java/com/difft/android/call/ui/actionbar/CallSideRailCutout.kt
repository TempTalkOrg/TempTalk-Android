package com.difft.android.call.ui.actionbar

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import com.difft.android.call.LCallUiConstants

/** How far the rail's top and bottom edges must move in to clear a display cutout, in dp. */
internal data class RailCutoutClearance(val topDp: Float, val bottomDp: Float)

/**
 * Vertical clearance for the cutout rects (window dp) that overlap the rail's horizontal band at
 * the end edge (the left one in RTL). A rect in the window's upper half pushes the top down to its
 * bottom, one in the lower half pushes the bottom up to its top. Nothing is horizontal: a cutout
 * never widens the rail or moves its column (#1451).
 */
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

/**
 * Top of a band [heightDp] tall that starts at [baseTopDp] and spans [left]..[right] (window dp),
 * lowered to the bottom of every cutout rect in the window's upper half that overlaps it. Rects go
 * top-down, so a band pushed below one still clears the next. No overlap returns [baseTopDp] (#1451).
 */
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

/**
 * Top of the title bar row (window dp), [baseTopDp] being the status-bar rule it already follows.
 * Without a rail the row holds minimize at the start and the centred title, so its whole width
 * clears the cutout. With a rail minimize is on the rail and only the centred title is left: the
 * band drops the rail's width at both edges, and [alignWithRail] keeps the row level with the
 * rail's minimize, which already clears the cutout in its own band (#1451).
 */
internal fun titleRowTopDp(
    cutouts: List<Rect>,
    windowWidthDp: Float,
    windowHeightDp: Float,
    baseTopDp: Float,
    rail: SideRail? = null,
    alignWithRail: Boolean = false,
): Float {
    val edge = rail?.widthDp?.toFloat() ?: 0f
    val cleared = cutoutClearedTopDp(
        cutouts, edge, windowWidthDp - edge, windowHeightDp, baseTopDp, LCallUiConstants.TOP_BAR_HEIGHT_DP.toFloat(),
    )
    if (rail == null || !alignWithRail) return cleared
    return maxOf(cleared, rail.topInset.value - LCallUiConstants.TOP_BAR_MARGIN_TOP_DP)
}

/**
 * [titleRowTopDp] for the current window. The cutout comes from its rects, not the side insets,
 * which flip for a moment while the bars toggle; [baseTop] stays the caller's own status-bar rule.
 */
@Composable
internal fun rememberTitleRowTop(baseTop: Dp, alignWithRail: Boolean): Dp {
    val window = rememberCallWindowSizeDp()
    val cutouts = rememberDisplayCutoutRectsDp()
    val rail = rememberCallSideRail()
    return remember(window, cutouts, rail, baseTop, alignWithRail) {
        titleRowTopDp(cutouts, window.width, window.height, baseTop.value, rail, alignWithRail).dp
    }
}

/**
 * The title row's top outside a screen share, below the shown status bar: the inset content that
 * reserves the title band starts from, so it moves with the row (#1451).
 */
@Composable
internal fun rememberTitleRowTopOutsideShare(): Dp =
    rememberTitleRowTop(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), alignWithRail = true)

/**
 * The display cutout's bounding rects in window dp. Compose exposes the cutout only as side
 * insets; reading them subscribes to every inset dispatch, after which the root insets hold the
 * matching rects. The call content fills the window edge to edge, so window coordinates are the
 * content's own.
 */
@Composable
internal fun rememberDisplayCutoutRectsDp(): List<Rect> {
    val view = LocalView.current
    val density = LocalDensity.current
    val cutout = WindowInsets.displayCutout
    val left = cutout.getLeft(density, LayoutDirection.Ltr)
    val top = cutout.getTop(density)
    val right = cutout.getRight(density, LayoutDirection.Ltr)
    val bottom = cutout.getBottom(density)
    // A resize or rotation can move the rects while the side insets stay equal, so the window size keys it too.
    val window = rememberCallWindowSizeDp()
    return remember(view, density, left, top, right, bottom, window) {
        val scale = density.density
        ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty().map {
            Rect(it.left / scale, it.top / scale, it.right / scale, it.bottom / scale)
        }
    }
}
