package com.difft.android.call.ui.actionbar

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.difft.android.call.LCallUiConstants

/**
 * How the call action bar is laid out. Ordered from the roomiest to the tightest fit; the
 * planner walks this order and stops at the first layout whose width budget fits.
 */
enum class ActionBarLayout {
    /** 1v1 portrait phones: Mute / Video / Speaker over Emoji / More / End, each with a label. */
    TWO_ROW,

    /** Emoji pinned start, End pinned end, the control group centred between them. */
    SPLIT,

    /** One centred row: Emoji Mute Video Speaker More End. */
    FULL_ROW,

    /** One centred row without Emoji; Emoji becomes a 40dp pill above the row's start edge. */
    EMOJI_OUTSIDE,

    /** [EMOJI_OUTSIDE] shrunk to 40dp buttons / 20dp icons / 8dp gaps. */
    COMPACT,
}

/** Controls that did not make it into the bar and therefore appear in the More sheet. */
enum class ActionBarQuickAction { INVITE, PEOPLE }

/**
 * Resolved geometry for one bar. Everything a renderer or a neighbouring layout needs to
 * reserve space is derived here so no consumer re-implements the bar's internals.
 */
data class ActionBarPlan(
    val layout: ActionBarLayout,
    val isGroup: Boolean,
    /** Diameter of one round control. */
    val buttonSizeDp: Int,
    /** Glyph size inside a control. */
    val iconSizeDp: Int,
    /** Gap between controls inside a row. */
    val gapDp: Int,
    /** Distance from the screen's bottom edge to the bar's bottom edge. */
    val bottomMarginDp: Int,
) {
    val emojiInline: Boolean
        get() = layout != ActionBarLayout.EMOJI_OUTSIDE && layout != ActionBarLayout.COMPACT

    val showInvite: Boolean get() = layout == ActionBarLayout.SPLIT

    val showPeople: Boolean get() = layout == ActionBarLayout.SPLIT && isGroup

    val moreQuickActions: List<ActionBarQuickAction>
        get() = when {
            layout == ActionBarLayout.SPLIT -> emptyList()
            layout == ActionBarLayout.TWO_ROW || !isGroup -> listOf(ActionBarQuickAction.INVITE)
            else -> listOf(ActionBarQuickAction.INVITE, ActionBarQuickAction.PEOPLE)
        }

    /** 1v1: a plain circle. Group: the leave circle plus the chevron tail, overlapped. */
    val endButtonWidthDp: Int
        get() = if (!isGroup) buttonSizeDp else CallActionBarPlanner.groupEndWidth(buttonSizeDp)

    /** Rendered height of the bar itself (no margins). */
    val barHeightDp: Int
        get() = if (layout == ActionBarLayout.TWO_ROW) {
            CallActionBarPlanner.TWO_ROW_HEIGHT_DP
        } else {
            buttonSizeDp
        }

    /** Extra band above the bar taken by the outside Emoji pill (its size plus the gap). */
    val outsideEmojiReserveDp: Int
        get() = if (emojiInline) 0 else CallActionBarPlanner.OUTSIDE_EMOJI_SIZE_DP + CallActionBarPlanner.OUTSIDE_EMOJI_GAP_DP

    /**
     * Vertical space from the screen bottom to the top of the whole chrome stack (bar plus
     * outside Emoji, or the two-row video backplate). Grid and barrage layouts reserve at least
     * this much.
     */
    val chromeBottomReserveDp: Int
        get() = if (layout == ActionBarLayout.TWO_ROW) {
            // Plate case is the taller of the two: 28 + 16 + rows + 16 = rows + 60.
            CallActionBarPlanner.TWO_ROW_PLATE_BOTTOM_DP + CallActionBarPlanner.TWO_ROW_PLATE_PADDING_DP * 2 + barHeightDp
        } else {
            bottomMarginDp + barHeightDp + outsideEmojiReserveDp
        }
}

/**
 * Pure width-budget planner. Every threshold is derived from control sizes and gaps at call
 * time; nothing here keys on a device model, a fold sensor or a fixed breakpoint.
 */
object CallActionBarPlanner {
    /** Bar inset from each screen edge; the width budget is `width - 2 * H_INSET`. */
    const val H_INSET_DP = 16

    const val BUTTON_DP = LCallUiConstants.BOTTOM_BAR_CONTROL_SIZE_DP  // 48
    const val ICON_DP = 24
    const val GAP_DP = 12
    const val COMPACT_BUTTON_DP = 40
    const val COMPACT_ICON_DP = 20
    const val COMPACT_GAP_DP = 8

    /** Group End: leave circle plus a chevron tail that overlaps it by half a circle. */
    const val GROUP_END_TAIL_DP = 54
    const val COMPACT_GROUP_END_TAIL_DP = 45

    /** [ActionBarLayout.SPLIT]: air between each side column and the centred control group. */
    const val SPLIT_SIDE_GAP_DP = 48

    const val OUTSIDE_EMOJI_SIZE_DP = 40
    const val OUTSIDE_EMOJI_ICON_DP = 20
    /** Vertical gap between the outside Emoji pill and the bar's top edge. */
    const val OUTSIDE_EMOJI_GAP_DP = 16

    /** Two-row geometry: labelled button = 48 + 8 + 20-line label; rows 24 apart. */
    const val LABEL_GAP_DP = 8
    const val LABEL_LINE_HEIGHT_DP = 20
    const val LABELLED_BUTTON_HEIGHT_DP = BUTTON_DP + LABEL_GAP_DP + LABEL_LINE_HEIGHT_DP  // 76
    const val TWO_ROW_GAP_DP = 24
    const val TWO_ROW_HEIGHT_DP = LABELLED_BUTTON_HEIGHT_DP * 2 + TWO_ROW_GAP_DP  // 176
    const val TWO_ROW_MAX_WIDTH_DP = 343
    /** Video backplate behind the two rows: vertical padding and its own bottom margin. */
    const val TWO_ROW_PLATE_PADDING_DP = 16
    const val TWO_ROW_PLATE_BOTTOM_DP = 28

    const val BOTTOM_PORTRAIT_DP = 48
    const val BOTTOM_LANDSCAPE_DP = 20
    const val BOTTOM_TWO_ROW_DP = 44

    /**
     * Two rows are for tall portrait phones only. A squat window — folded cover screens and
     * tablets in portrait — reads as a single row even when it is narrow, so the aspect ratio
     * (height / width) is the discriminator, not the device. 1.7 admits every phone from 16:9
     * (1.78) upwards and still keeps a 10:16 cover screen (1.58) and 3:4 tablets on one row.
     */
    const val TWO_ROW_MIN_ASPECT = 1.7f

    fun groupEndWidth(buttonSizeDp: Int): Int = if (buttonSizeDp == COMPACT_BUTTON_DP) {
        COMPACT_BUTTON_DP + COMPACT_GROUP_END_TAIL_DP - COMPACT_BUTTON_DP / 2  // 65
    } else {
        BUTTON_DP + GROUP_END_TAIL_DP - BUTTON_DP / 2  // 78
    }

    /** Width of `count` controls of `button` dp separated by `gap` dp. */
    fun rowWidth(count: Int, buttonDp: Int, gapDp: Int): Int = count * buttonDp + (count - 1) * gapDp

    /** Width [ActionBarLayout.SPLIT] needs: 1v1 480, group 600. */
    fun splitNeed(isGroup: Boolean): Int {
        val mid = if (isGroup) 6 else 5  // mic video speaker invite (people) more
        val side = maxOf(BUTTON_DP, if (isGroup) groupEndWidth(BUTTON_DP) else BUTTON_DP)
        return rowWidth(mid, BUTTON_DP, GAP_DP) + side * 2 + SPLIT_SIDE_GAP_DP * 2
    }

    /** Width a single centred row needs: `mid` controls, one gap, then the End control. */
    fun singleRowNeed(midCount: Int, buttonDp: Int, gapDp: Int, isGroup: Boolean): Int {
        val end = if (isGroup) groupEndWidth(buttonDp) else buttonDp
        return rowWidth(midCount, buttonDp, gapDp) + gapDp + end
    }

    fun resolve(widthDp: Int, heightDp: Int, isGroup: Boolean, isLandscape: Boolean): ActionBarPlan {
        val usable = widthDp - H_INSET_DP * 2
        val bottom = if (isLandscape) BOTTOM_LANDSCAPE_DP else BOTTOM_PORTRAIT_DP
        fun single(layout: ActionBarLayout, compact: Boolean) = ActionBarPlan(
            layout = layout,
            isGroup = isGroup,
            buttonSizeDp = if (compact) COMPACT_BUTTON_DP else BUTTON_DP,
            iconSizeDp = if (compact) COMPACT_ICON_DP else ICON_DP,
            gapDp = if (compact) COMPACT_GAP_DP else GAP_DP,
            bottomMarginDp = bottom,
        )

        if (splitNeed(isGroup) <= usable) return single(ActionBarLayout.SPLIT, compact = false)

        val tall = widthDp > 0 && heightDp.toFloat() / widthDp >= TWO_ROW_MIN_ASPECT
        if (!isGroup && !isLandscape && tall) {
            return ActionBarPlan(
                layout = ActionBarLayout.TWO_ROW,
                isGroup = false,
                buttonSizeDp = BUTTON_DP,
                iconSizeDp = ICON_DP,
                gapDp = GAP_DP,
                bottomMarginDp = BOTTOM_TWO_ROW_DP,
            )
        }

        return when {
            singleRowNeed(5, BUTTON_DP, GAP_DP, isGroup) <= usable -> single(ActionBarLayout.FULL_ROW, compact = false)
            singleRowNeed(4, BUTTON_DP, GAP_DP, isGroup) <= usable -> single(ActionBarLayout.EMOJI_OUTSIDE, compact = false)
            else -> single(ActionBarLayout.COMPACT, compact = true)
        }
    }
}

/**
 * Plan for the current window. Reads the real container size so fold / unfold and rotation
 * re-plan on the next layout pass; only the very first composition, before any layout pass has
 * populated `containerSize`, falls back to [Configuration] (same idiom as the participant grids).
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun rememberCallActionBarPlan(isGroup: Boolean): ActionBarPlan {
    val configuration = LocalConfiguration.current
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val widthDp: Int
    val heightDp: Int
    if (containerSize.width > 0 && containerSize.height > 0) {
        with(density) {
            widthDp = containerSize.width.toDp().value.toInt()
            heightDp = containerSize.height.toDp().value.toInt()
        }
    } else {
        widthDp = configuration.screenWidthDp
        heightDp = configuration.screenHeightDp
    }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return remember(widthDp, heightDp, isGroup, isLandscape) {
        CallActionBarPlanner.resolve(widthDp, heightDp, isGroup, isLandscape)
    }
}
