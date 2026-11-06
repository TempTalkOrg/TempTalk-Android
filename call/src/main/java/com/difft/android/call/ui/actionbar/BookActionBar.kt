package com.difft.android.call.ui.actionbar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.difft.android.call.ui.layout.FOLD_PAD_DP
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.difft.android.call.layout.Rect as LayoutRect

/** What the book posture's toolbar leaves free around the fold, besides the fold itself (guide §5.2). */
private const val BOOK_FOLD_CLEARANCE_DP = 32.0

/** Where the two halves of the book toolbar end and start, window dp. */
internal data class BookBarSplit(val leftEndDp: Double, val rightStartDp: Double)

/**
 * The gap centred on the fold line, as wide as the fold plus [BOOK_FOLD_CLEARANCE_DP]. [fold] is the
 * fold the layout used, padded [FOLD_PAD_DP] on each side, which the gap does not count twice.
 */
internal fun bookBarSplit(fold: LayoutRect): BookBarSplit {
    val center = fold.x + fold.w / 2
    val foldWidth = max(0.0, fold.w - 2 * FOLD_PAD_DP)
    val half = (foldWidth + BOOK_FOLD_CLEARANCE_DP) / 2
    return BookBarSplit(center - half, center + half)
}

/** The width each group may take in a window [windowWidthDp] wide: its side of the gap, less the bar's inset. */
internal fun bookBarBudgets(split: BookBarSplit, windowWidthDp: Double, insetDp: Double): Pair<Double, Double> =
    Pair(max(0.0, split.leftEndDp - insetDp), max(0.0, windowWidthDp - insetDp - split.rightStartDp))

/** The scale a group [widthPx] wide takes to fit [budgetPx]: 1 when it fits, smaller (compact) when not. */
internal fun bookGroupScale(widthPx: Int, budgetPx: Int): Float =
    if (widthPx <= 0 || widthPx <= budgetPx) 1f else max(0f, budgetPx.toFloat() / widthPx)

/** The controls left and right of the fold, in order; hang-up last on the right. */
internal fun bookBarGroups(plan: ActionBarPlan): Pair<List<DockSlot>, List<DockSlot>> = Pair(
    listOf(DockSlot.EMOJI, DockSlot.MIC, DockSlot.VIDEO, DockSlot.SPEAKER),
    listOfNotNull(DockSlot.INVITE.takeIf { plan.showInvite }, DockSlot.PEOPLE.takeIf { plan.showPeople }, DockSlot.MORE, DockSlot.END),
)

/**
 * The bottom bar in the book posture: its controls in two groups, one each side of the [fold]
 * (window dp), so none sits on the hinge. A group wider than its side shrinks to fit it. Every
 * control is placed in window coordinates, never mirrored, so hang-up stays last on the right; the
 * bar shows once its own position in the window is known.
 */
@Composable
internal fun BookActionBar(plan: ActionBarPlan, slots: ActionBarSlots, fold: LayoutRect, modifier: Modifier = Modifier) {
    val (left, right) = bookBarGroups(plan)
    val split = bookBarSplit(fold)
    var originX by remember { mutableStateOf<Float?>(null) }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(plan.buttonSizeDp.dp)
            .testTag("call_action_bar_book")
            .onGloballyPositioned { originX = it.positionInWindow().x },
        content = {
            BookGroup(left, plan.gapDp, slots)
            BookGroup(right, plan.gapDp, slots)
        },
    ) { measurables, constraints ->
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val (leftGroup, rightGroup) = measurables.map { it.measure(loose) }
        val origin = originX
        val x0 = origin ?: 0f
        val windowWidthDp = (x0 + constraints.maxWidth).toDp().value.toDouble()
        val (leftBudget, rightBudget) = bookBarBudgets(split, windowWidthDp, CallActionBarPlanner.H_INSET_DP.toDouble())
        val leftScale = bookGroupScale(leftGroup.width, leftBudget.dp.roundToPx())
        val rightScale = bookGroupScale(rightGroup.width, rightBudget.dp.roundToPx())
        val leftEnd = (split.leftEndDp.dp.toPx() - x0).roundToInt()
        val rightStart = (split.rightStartDp.dp.toPx() - x0).roundToInt()
        val alpha = if (origin == null) 0f else 1f
        layout(constraints.maxWidth, constraints.maxHeight) {
            leftGroup.placeWithLayer(leftEnd - leftGroup.width, (constraints.maxHeight - leftGroup.height) / 2) {
                scaleX = leftScale
                scaleY = leftScale
                transformOrigin = TransformOrigin(1f, 0.5f)
                this.alpha = alpha
            }
            rightGroup.placeWithLayer(rightStart, (constraints.maxHeight - rightGroup.height) / 2) {
                scaleX = rightScale
                scaleY = rightScale
                transformOrigin = TransformOrigin(0f, 0.5f)
                this.alpha = alpha
            }
        }
    }
}

/** One group's controls left to right, [gapDp] apart, placed absolutely (no RTL mirroring). */
@Composable
private fun BookGroup(group: List<DockSlot>, gapDp: Int, slots: ActionBarSlots) {
    Layout(content = { group.forEach { slot -> key(slot) { slots.forDock(slot)() } } }) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
        val gap = gapDp.dp.roundToPx()
        val width = placeables.sumOf { it.width } + gap * max(0, placeables.size - 1)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, min(height, constraints.maxHeight)) {
            var x = 0
            placeables.forEach { p ->
                p.place(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}
