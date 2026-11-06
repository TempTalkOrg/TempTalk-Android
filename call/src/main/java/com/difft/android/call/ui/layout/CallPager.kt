package com.difft.android.call.ui.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.Palette
import com.difft.android.call.R
import com.difft.android.call.layout.CallLayoutController
import com.difft.android.call.layout.Pager
import com.difft.android.call.layout.Point
import com.difft.android.call.layout.Rect
import com.difft.android.call.layout.Tile
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

// Page indicator geometry, guide §5.1 (dp).
private const val SEGMENT_WIDTH = 3.0
private const val SEGMENT_SPACING_MAX = 36.0
private const val SEGMENT_GAP_MAX = 4.0
private const val BAR_EDGE_INSET = 4.0
private const val SEGMENT_TAP_WIDTH = 14.0
private const val PILL_GAP = 6.0
private const val LAST_TILE_INSET = 8
private const val PAGE_SWIPE_DP = 40

// Pill timing, guide §5.1.
private const val PILL_SHOWN_MS = 3_500L
private const val PILL_FADE_MS = 300

// Guide §5.1 colours (#F1F3F4, #E8EAED at 35%, #202124) as their nearest Palette steps; no
// DifftTheme token matches them.
private val SegmentCurrent = Palette.Gray10
private val SegmentOther = Palette.Gray50.copy(alpha = 0.35f)
private val PillGround = Palette.Gray10
private val PillText = Palette.Gray800

/**
 * The vertical page bar beside the grid (guide §5.1): one segment per page, [spacing] apart, each
 * [segment] long and [SEGMENT_WIDTH] wide; [x] is its left edge, [top] the first segment's top, in
 * window dp.
 */
internal data class PagerBar(val x: Double, val top: Double, val spacing: Double, val segment: Double, val pages: Int) {
    fun segmentTop(index: Int): Double = top + index * spacing

    fun segmentCenterY(index: Int): Double = segmentTop(index) + segment / 2

    /** The 14dp-wide tap target of segment [index], centred on the bar and as tall as its spacing. */
    fun tapRect(index: Int): Rect =
        Rect(x + SEGMENT_WIDTH / 2 - SEGMENT_TAP_WIDTH / 2, segmentTop(index) - (spacing - segment) / 2, SEGMENT_TAP_WIDTH, spacing)
}

/**
 * The bar for [pages] pages beside [area]: its right edge [BAR_EDGE_INSET] inside the area's, centred
 * vertically; segments are compressed to `min(36, (h + 4) / pages)` apart with a gap of
 * `min(4, spacing / 4)` once they no longer fit.
 */
internal fun pagerBar(area: Rect, pages: Int): PagerBar {
    val count = pages.coerceAtLeast(1)
    val spacing = min(SEGMENT_SPACING_MAX, (area.h + SEGMENT_GAP_MAX) / count)
    val gap = min(SEGMENT_GAP_MAX, spacing / 4)
    val segment = spacing - gap
    val height = count * spacing - gap
    val right = area.x + area.w - BAR_EDGE_INSET
    return PagerBar(x = right - SEGMENT_WIDTH, top = area.y + (area.h - height) / 2, spacing = spacing, segment = segment, pages = count)
}

/** Where the page pill sits: its right edge [PILL_GAP] left of the bar, centred on the current segment. */
internal fun pagerPillAnchor(bar: PagerBar, page: Int): Point = Point(bar.x - PILL_GAP, bar.segmentCenterY(page))

/** The page pill's rect for a pill [w] × [h]: right edge and centre at [pagerPillAnchor]. */
internal fun pagerPillRect(bar: PagerBar, page: Int, w: Double, h: Double): Rect {
    val anchor = pagerPillAnchor(bar, page)
    return Rect(anchor.x - w, anchor.y - h / 2, w, h)
}

/**
 * The last card's label rect for a label [w] × [h] (guide §5.1): top right of [card] inset
 * [LAST_TILE_INSET], its right edge no further right than [PILL_GAP] left of the bar; when that
 * overlaps the page [pill] (its position whether or not it is shown, so a fading pill never moves
 * the label) the label moves left of the pill by [PILL_GAP].
 */
internal fun lastTileLabelRect(card: Rect, bar: PagerBar, pill: Rect, w: Double, h: Double): Rect {
    var right = min(card.x + card.w - LAST_TILE_INSET, bar.x - PILL_GAP)
    val top = card.y + LAST_TILE_INSET
    val overlaps = right - w < pill.x + pill.w && right > pill.x && top < pill.y + pill.h && top + h > pill.y
    if (overlaps) right = pill.x - PILL_GAP
    return Rect(right - w, top, w, h)
}

/**
 * The page and page count the pill last appeared for. The stage keeps it, so the pill does not come
 * back when the indicator re-enters (leaving picture in picture, a share ending), only on a change.
 */
internal class PagerPillMemory {
    private var last: Pair<Int, Int>? = null

    /** Whether the pill appears for [page] of [pages]: only when either differs from the last time it did. */
    fun appearsFor(page: Int, pages: Int): Boolean {
        val key = page to pages
        if (key == last) return false
        last = key
        return true
    }
}

/** The page turn for a vertical swipe of [dy] px: up past [threshold] is the next page, down the previous. */
internal fun pageTurnFor(dy: Float, threshold: Float): Int = when {
    abs(dy) <= threshold -> 0
    dy < 0 -> 1
    else -> -1
}

/** Turns a page on a vertical swipe longer than [PAGE_SWIPE_DP] (guide §5.1). */
internal fun Modifier.pageSwipe(enabled: Boolean, controller: CallLayoutController): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(controller) {
            val threshold = PAGE_SWIPE_DP.dp.toPx()
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    val turn = pageTurnFor(total, threshold)
                    if (turn != 0) controller.turnPage(turn)
                },
                onDragCancel = { total = 0f },
            ) { change, dy ->
                change.consume()
                total += dy
            }
        }
    }

/**
 * The page indicator, the page pill and the label on the last card (guide §5.1). [origin] is the
 * stage's position in the window (dp), subtracted from every plan coordinate; positions are absolute,
 * never mirrored for RTL, like the cards. Taps here never reach the stage.
 */
@Composable
internal fun CallPagerOverlay(
    pager: Pager,
    lastTile: Tile?,
    origin: Offset,
    controller: CallLayoutController,
    pillMemory: PagerPillMemory,
) {
    val bar = remember(pager.area, pager.pages) { pagerBar(pager.area, pager.pages) }
    Box(Modifier.fillMaxSize().testTag("call_pager"), contentAlignment = AbsoluteAlignment.TopLeft) {
        for (index in 0 until bar.pages) {
            Box(
                Modifier
                    .absoluteOffset((bar.x - origin.x).dp, (bar.segmentTop(index) - origin.y).dp)
                    .size(SEGMENT_WIDTH.dp, bar.segment.dp)
                    .testTag("call_pager_segment_$index")
                    .shadow(1.dp, DifftTheme.radius.pill)
                    .background(if (index == pager.page) SegmentCurrent else SegmentOther, DifftTheme.radius.pill),
            )
            val tap = bar.tapRect(index)
            Box(
                Modifier
                    .absoluteOffset((tap.x - origin.x).dp, (tap.y - origin.y).dp)
                    .size(tap.w.dp, tap.h.dp)
                    .pointerInput(index) { detectTapGestures { controller.showPage(index) } },
            )
        }
        PagerLabels(pager, bar, lastTile, origin, controller, pillMemory)
    }
}


/**
 * The page pill (the current page number, shown when the page or the page count changes and faded
 * out after [PILL_SHOWN_MS]; a relayout of the same page keeps it hidden) and the last card's label
 * ("N more" on page 1, "back to page 1" after it), placed together so the label can keep clear of
 * the pill's position ([lastTileLabelRect]). Taps on either stay here.
 */
@Composable
private fun PagerLabels(
    pager: Pager,
    bar: PagerBar,
    lastTile: Tile?,
    origin: Offset,
    controller: CallLayoutController,
    memory: PagerPillMemory,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(pager.page, pager.pages) {
        if (!memory.appearsFor(pager.page, pager.pages)) return@LaunchedEffect
        shown = true
        delay(PILL_SHOWN_MS)
        shown = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (shown) snap() else tween(PILL_FADE_MS),
        label = "pagerPillAlpha",
    )
    val firstPage = pager.page == 0
    val labelText = when {
        lastTile == null -> null
        firstPage && pager.later > 0 -> pluralStringResource(R.plurals.call_pager_later_people, pager.later, pager.later)
        !firstPage -> stringResource(R.string.call_pager_back_to_first)
        else -> null
    }
    val pillVisible = alpha > 0f
    Layout(
        content = {
            PagerLabel(
                text = "${pager.page + 1}",
                modifier = Modifier
                    .testTag("call_pager_pill")
                    .graphicsLayer { this.alpha = alpha }
                    .pointerInput(pillVisible) { if (pillVisible) detectTapGestures {} },
            )
            if (labelText != null) {
                PagerLabel(
                    text = labelText,
                    modifier = Modifier
                        .testTag("call_pager_label")
                        .pointerInput(firstPage) { detectTapGestures { if (!firstPage) controller.showPage(0) } },
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val pill = measurables[0].measure(loose)
        val label = measurables.getOrNull(1)?.measure(loose)
        val scale = density.toDouble()
        val pillRect = pagerPillRect(bar, pager.page, pill.width / scale, pill.height / scale)
        val labelRect = if (label != null && lastTile != null) {
            lastTileLabelRect(lastTile.rect, bar, pillRect, label.width / scale, label.height / scale)
        } else {
            null
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (pillVisible) pill.place(pillRect.toStagePx(origin, density).topLeft)
            if (label != null && labelRect != null) label.place(labelRect.toStagePx(origin, density).topLeft)
        }
    }
}

/** The light pill with dark text the page label and the last-card label share (guide §5.1). */
@Composable
private fun PagerLabel(text: String, modifier: Modifier) {
    Box(modifier) {
        Text(
            text = text,
            style = DifftTheme.typography.footnote,
            color = PillText,
            maxLines = 1,
            modifier = Modifier
                .background(PillGround, DifftTheme.radius.pill)
                .padding(horizontal = PILL_PADDING_H.dp, vertical = PILL_PADDING_V.dp),
        )
    }
}

private const val PILL_PADDING_H = 8
private const val PILL_PADDING_V = 2
