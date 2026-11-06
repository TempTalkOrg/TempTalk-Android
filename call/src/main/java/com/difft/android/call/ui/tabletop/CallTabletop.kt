package com.difft.android.call.ui.tabletop

import com.difft.android.call.LCallUiConstants
import com.difft.android.call.layout.LayoutPlan
import com.difft.android.call.layout.Posture
import com.difft.android.call.ui.actionbar.ActionBarPlan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize

/** Half-open postures for calls. Sizes are dp unless named px. */
internal object CallTabletopSpec {
    const val GAP_DP = LCallUiConstants.CHROME_CONTENT_GAP_DP.toFloat()

    /** Console: 16 top + 84 row + 24 gap + 84 row + 24 bottom. */
    const val CONSOLE_HEIGHT_DP = 232f

    /** Console: four columns of 84, each a 56dp control and its label, inside 16 a side. */
    const val CONSOLE_WIDTH_DP = 368f

    /**
     * The console's size, given to the layout, which places it below the fold (`LayoutPlan.controls`)
     * and the 1v1 floating window around it (call-layout decision 39). The members panel and the
     * invite sheet cover the whole region below the fold while open.
     */
    val CONSOLE_SIZE = LayoutSize(CONSOLE_WIDTH_DP.toDouble(), CONSOLE_HEIGHT_DP.toDouble())
}

internal enum class CallFoldState { FLAT, HALF_OPENED }

/** A rect in px, JVM-pure so the resolver and its tests need no android.graphics. */
internal data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** One folding feature: [bounds] in activity-window px, as Jetpack WindowManager reports them. */
internal data class CallFold(val state: CallFoldState, val horizontal: Boolean, val occluding: Boolean, val bounds: PxRect)

/** One window-layout report; an empty [folds] means no folding feature. */
internal data class CallFoldReport(val folds: List<CallFold>)

/**
 * A half-open fold in call-root px, or the tabletop geometry the page draws around it. [bandTopPx]
 * to [bandBottomPx] is the fold across its thin axis: top to bottom for a [horizontal] fold (the
 * tabletop posture), left to right for a vertical one (book). Equal re-emissions compare equal.
 */
data class TabletopGeometry(
    val bandTopPx: Int,
    val bandBottomPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val horizontal: Boolean = true,
    // Where the layout put the console (`LayoutPlan.controls`, window dp); null outside the plan.
    val controls: LayoutRect? = null,
) {
    val bandTopDp: Float get() = bandTopPx / density
    val bandBottomDp: Float get() = bandBottomPx / density
    val widthDp: Float get() = widthPx / density
    val heightDp: Float get() = heightPx / density

    val dockTopDp: Float get() = bandBottomDp + CallTabletopSpec.GAP_DP
    val dockHeightDp: Float get() = heightDp - dockTopDp

    /** The dock region's height in px, from its top to the root's bottom; for views outside Compose. */
    val dockHeightPx: Int get() = heightPx - (dockTopDp * density).roundToInt()

    /**
     * The console in window dp: where the layout put it, else (a plan laid out before the layout knew
     * its size) centred at the window bottom above the [bottomInsetDp] system inset.
     */
    fun consoleDp(bottomInsetDp: Float = 0f): LayoutRect = controls ?: LayoutRect(
        ((widthDp - CallTabletopSpec.CONSOLE_WIDTH_DP) / 2f).coerceAtLeast(0f).toDouble(),
        (heightDp - bottomInsetDp - CallTabletopSpec.CONSOLE_HEIGHT_DP).toDouble(),
        min(widthDp, CallTabletopSpec.CONSOLE_WIDTH_DP).toDouble(),
        CallTabletopSpec.CONSOLE_HEIGHT_DP.toDouble(),
    )
}

internal sealed interface TabletopResolution {
    /** No folding feature, or none crosses the call window. */
    data object NoFold : TabletopResolution

    /** A fold lying flat; keyed on the root size so a resize is a new input. */
    data class Flat(val widthPx: Int, val heightPx: Int) : TabletopResolution

    /** A half-open fold, horizontal or vertical. */
    data class Accepted(val geometry: TabletopGeometry) : TabletopResolution
}

/** A fold mapped into call-root px. */
private class LocalFold(val fold: CallFold, val rect: PxRect)

/**
 * Whether a fold report puts the call root ([rootLeftPx], [rootTopPx] is its position in the
 * activity window) half open, and where the fold lies: the first half-open fold crossing the root,
 * either orientation. Every size rule is the layout's (design §4.5); this only feeds the debounce.
 */
internal fun resolveTabletop(
    report: CallFoldReport?,
    rootLeftPx: Int,
    rootTopPx: Int,
    rootWidthPx: Int,
    rootHeightPx: Int,
    density: Float,
): TabletopResolution {
    if (report == null || report.folds.isEmpty() || rootWidthPx <= 0 || rootHeightPx <= 0 || density <= 0f) {
        return TabletopResolution.NoFold
    }
    val crossing = report.folds.mapNotNull { localFold(it, rootLeftPx, rootTopPx, rootWidthPx, rootHeightPx) }
    if (crossing.isEmpty()) return TabletopResolution.NoFold
    val fold = crossing.firstOrNull { it.fold.state == CallFoldState.HALF_OPENED }
        ?: return TabletopResolution.Flat(rootWidthPx, rootHeightPx)
    val r = fold.rect
    return TabletopResolution.Accepted(
        TabletopGeometry(
            bandTopPx = if (fold.fold.horizontal) r.top else r.left,
            bandBottomPx = if (fold.fold.horizontal) r.bottom else r.right,
            widthPx = rootWidthPx,
            heightPx = rootHeightPx,
            density = density,
            horizontal = fold.fold.horizontal,
        ),
    )
}

/** [fold] in root px when it crosses the root's interior, else null. */
private fun localFold(fold: CallFold, leftPx: Int, topPx: Int, widthPx: Int, heightPx: Int): LocalFold? {
    val b = fold.bounds
    val r = PxRect(b.left - leftPx, b.top - topPx, b.right - leftPx, b.bottom - topPx)
    return if (fold.horizontal) {
        val overlap = min(r.right, widthPx) - max(r.left, 0)
        if (r.top > 0 && r.bottom < heightPx && overlap > 0) LocalFold(fold, r) else null
    } else {
        val overlap = min(r.bottom, heightPx) - max(r.top, 0)
        if (r.left > 0 && r.right < widthPx && overlap > 0) LocalFold(fold, r) else null
    }
}

/** The debounced fold as the layout's fold: window dp across the whole window, before padding. */
internal fun TabletopGeometry.foldDp(): LayoutRect = if (horizontal) {
    LayoutRect(0.0, bandTopDp.toDouble(), widthDp.toDouble(), (bandBottomDp - bandTopDp).toDouble())
} else {
    LayoutRect(bandTopDp.toDouble(), 0.0, (bandBottomDp - bandTopDp).toDouble(), heightDp.toDouble())
}

/**
 * The tabletop geometry the page draws from the [plan]: while it is in the tabletop posture, the
 * fold the plan was laid out with ([fold], window dp) above, the plan's dock below and the console
 * where the plan put it; null otherwise. The call window is edge to edge, so window dp are the root's.
 */
internal fun planTabletop(plan: LayoutPlan?, fold: LayoutRect?, window: LayoutSize, density: Float): TabletopGeometry? {
    if (plan?.posture != Posture.TABLETOP || fold == null || density <= 0f) return null
    val dock = plan.dock ?: return null
    return TabletopGeometry(
        bandTopPx = (fold.y * density).roundToInt(),
        bandBottomPx = ((dock.y - CallTabletopSpec.GAP_DP) * density).roundToInt(),
        widthPx = (window.w * density).roundToInt(),
        heightPx = (window.h * density).roundToInt(),
        density = density,
        controls = plan.controls,
    )
}

/**
 * What the content keeps clear at the window bottom: in the tabletop posture only the [navBottomDp]
 * inset, both with the controls shown and hidden, since the layout places the console itself
 * (`setTabletopControls`, call-layout decision 39); in the book posture one row of controls;
 * otherwise the bar's own reserve.
 */
internal fun bottomReserveDp(posture: Posture, plan: ActionBarPlan, navBottomDp: Float): Int =
    when (posture) {
        Posture.TABLETOP -> navBottomDp.roundToInt()
        Posture.BOOK -> plan.bottomMarginDp + plan.buttonSizeDp
        Posture.FLAT -> plan.chromeBottomReserveDp
    }
