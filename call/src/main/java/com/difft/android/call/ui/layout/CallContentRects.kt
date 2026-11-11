package com.difft.android.call.ui.layout

import com.difft.android.call.LCallUiConstants
import com.difft.android.call.ui.actionbar.SideRail
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.difft.android.call.layout.Insets as LayoutInsets
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize


private const val COMPACT_MAX_WIDTH_DP = 600.0
private const val COMPACT_SIDE_DP = 12.0
private const val WIDE_SIDE_DP = 16.0


internal const val FOLD_PAD_DP = 20.0


private const val FOLD_TOLERANCE_DP = 1.0

private const val GAP_DP = LCallUiConstants.CHROME_CONTENT_GAP_DP.toDouble()


internal data class CallContentRects(val shown: LayoutRect, val hidden: LayoutRect)

















@Suppress("LongParameterList")
internal fun callContentRects(
    window: LayoutSize,
    safe: LayoutInsets,
    statusBandDp: Double,
    barBottomReserveDp: Int,
    navigationBarDp: Double,
    rail: SideRail?,
    tabletop: Boolean,
    isRtl: Boolean = false,
): CallContentRects {
    val side = if (window.w < COMPACT_MAX_WIDTH_DP) COMPACT_SIDE_DP else WIDE_SIDE_DP
    val left = safe.left + side
    val right = window.w - safe.right - side

    val railBand = rail?.let { it.widthDp + GAP_DP }
    val shownLeft = if (railBand != null && isRtl) max(left, railBand) else left
    val shownRight = if (railBand != null && !isRtl) min(right, window.w - railBand) else right
    val shownTop = if (tabletop) GAP_DP else statusBandDp + LCallUiConstants.TOP_BAR_TOTAL_HEIGHT_DP + GAP_DP
    val shownBottom = if (rail != null) {
        window.h - max(safe.bottom + GAP_DP, rail.bottomInsetDp.toDouble())
    } else {
        window.h - barBottomReserveDp - GAP_DP
    }
    val hiddenBottom = if (tabletop) shownBottom else window.h - navigationBarDp - GAP_DP

    val hiddenSide = if (window.w < COMPACT_MAX_WIDTH_DP) COMPACT_SIDE_DP else GAP_DP

    val shown = rectOf(shownLeft, shownTop, shownRight, shownBottom)
    val hidden = rectOf(safe.left + hiddenSide, GAP_DP, window.w - safe.right - hiddenSide, hiddenBottom)
    return CallContentRects(shown, hidden)
}

private fun rectOf(left: Double, top: Double, right: Double, bottom: Double): LayoutRect =
    LayoutRect(left, top, max(0.0, right - left), max(0.0, bottom - top))


internal fun padFold(bounds: LayoutRect, vertical: Boolean): LayoutRect =
    if (vertical) {
        LayoutRect(bounds.x - FOLD_PAD_DP, bounds.y, bounds.w + 2 * FOLD_PAD_DP, bounds.h)
    } else {
        LayoutRect(bounds.x, bounds.y - FOLD_PAD_DP, bounds.w, bounds.h + 2 * FOLD_PAD_DP)
    }





internal fun foldInWindow(fold: LayoutRect?, window: LayoutSize): LayoutRect? {
    if (fold == null) return null
    val tol = FOLD_TOLERANCE_DP
    val inside = fold.x >= -tol && fold.y >= -tol && fold.x + fold.w <= window.w + tol && fold.y + fold.h <= window.h + tol
    val vertical = fold.h > fold.w
    val across = if (vertical) abs(fold.h - window.h) <= tol else abs(fold.w - window.w) <= tol
    return if (inside && across) fold else null
}
