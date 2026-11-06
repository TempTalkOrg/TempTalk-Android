package com.difft.android.call.ui.layout

import com.difft.android.call.LCallUiConstants
import com.difft.android.call.ui.actionbar.SideRail
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.difft.android.call.layout.Insets as LayoutInsets
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize

/** Windows narrower than this are compact: cards keep [COMPACT_SIDE_DP] from the sides. */
private const val COMPACT_MAX_WIDTH_DP = 600.0
private const val COMPACT_SIDE_DP = 12.0
private const val WIDE_SIDE_DP = 16.0

/** Kept clear on each side of a half-open fold, which is reported as a zero-width line. */
internal const val FOLD_PAD_DP = 20.0

/** Slack for px→dp rounding when matching a fold against the window. */
private const val FOLD_TOLERANCE_DP = 1.0

private const val GAP_DP = LCallUiConstants.CHROME_CONTENT_GAP_DP.toDouble()

/** The area cards may use with the call's bars shown and with them hidden, in window dp. */
internal data class CallContentRects(val shown: LayoutRect, val hidden: LayoutRect)

/**
 * The content rects for [CallLayoutController.setContent][com.difft.android.call.layout.CallLayoutController.setContent].
 *
 * Shown: the sides keep 12dp (compact) or 16dp inside [safe]; the top is below the title bar and
 * the status band, the bottom above the toolbar ([barBottomReserveDp]), each with the content gap.
 * A shown [rail] takes its column from the end side (and replaces the toolbar at the bottom). In
 * the tabletop posture the title bar floats over the cards, which start 8 from the top (decision 23).
 * Hidden: the bars and the rail are gone; top 8, bottom 8 above the navigation bar as it is shown
 * ([navigationBarDp], 0 while the call hides it, so the top and bottom margins match); in tabletop
 * the toolbar stays, so the shown bottom is kept. Both then step clear of display [cutouts].
 *
 * [statusBandDp] is the status bar's height whether or not it is shown, so toggling the bar never
 * changes which people fit on a page.
 */
@Suppress("LongParameterList")
internal fun callContentRects(
    window: LayoutSize,
    safe: LayoutInsets,
    statusBandDp: Double,
    barBottomReserveDp: Int,
    navigationBarDp: Double,
    rail: SideRail?,
    tabletop: Boolean,
    cutouts: List<LayoutRect>,
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

    val shown = rectOf(shownLeft, shownTop, shownRight, shownBottom)
    val hidden = rectOf(left, GAP_DP, right, hiddenBottom)
    return CallContentRects(avoidCutouts(shown, cutouts), avoidCutouts(hidden, cutouts))
}

private fun rectOf(left: Double, top: Double, right: Double, bottom: Double): LayoutRect =
    LayoutRect(left, top, max(0.0, right - left), max(0.0, bottom - top))

/**
 * Where a cutout's bounding box reaches into [content], the content gives up the side that loses
 * the least area (guide §4.2).
 */
internal fun avoidCutouts(content: LayoutRect, cutouts: List<LayoutRect>): LayoutRect {
    var c = content
    for (cut in cutouts) {
        val overlaps = cut.x < c.x + c.w && c.x < cut.x + cut.w && cut.y < c.y + c.h && c.y < cut.y + cut.h
        if (!overlaps) continue
        val options = listOf(
            LayoutRect(c.x, cut.y + cut.h, c.w, c.y + c.h - (cut.y + cut.h)),
            LayoutRect(c.x, c.y, c.w, cut.y - c.y),
            LayoutRect(cut.x + cut.w, c.y, c.x + c.w - (cut.x + cut.w), c.h),
            LayoutRect(c.x, c.y, cut.x - c.x, c.h),
        ).filter { it.w > 0 && it.h > 0 }
        c = options.maxByOrNull { it.w * it.h } ?: c
    }
    return c
}

/** A half-open fold, usually a zero-width line, padded [FOLD_PAD_DP] on each side across it. */
internal fun padFold(bounds: LayoutRect, vertical: Boolean): LayoutRect =
    if (vertical) {
        LayoutRect(bounds.x - FOLD_PAD_DP, bounds.y, bounds.w + 2 * FOLD_PAD_DP, bounds.h)
    } else {
        LayoutRect(bounds.x, bounds.y - FOLD_PAD_DP, bounds.w, bounds.h + 2 * FOLD_PAD_DP)
    }

/**
 * [fold] when it lies inside [window] and runs from one edge to the other; otherwise null, for
 * this report (a fold can arrive before the window has resized).
 */
internal fun foldInWindow(fold: LayoutRect?, window: LayoutSize): LayoutRect? {
    if (fold == null) return null
    val tol = FOLD_TOLERANCE_DP
    val inside = fold.x >= -tol && fold.y >= -tol && fold.x + fold.w <= window.w + tol && fold.y + fold.h <= window.h + tol
    val vertical = fold.h > fold.w
    val across = if (vertical) abs(fold.h - window.h) <= tol else abs(fold.w - window.w) <= tol
    return if (inside && across) fold else null
}
