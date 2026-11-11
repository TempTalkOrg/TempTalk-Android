package com.difft.android.call.ui.actionbar

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.difft.android.call.LCallUiConstants





enum class ActionBarLayout {
    
    TWO_ROW,

    
    SPLIT,

    
    FULL_ROW,

    
    EMOJI_OUTSIDE,

    
    COMPACT,
}





enum class ActionBarQuickAction { INVITE, PEOPLE, EMOJI }





data class ActionBarPlan(
    val layout: ActionBarLayout,
    val isGroup: Boolean,
    
    val buttonSizeDp: Int,
    
    val iconSizeDp: Int,
    
    val gapDp: Int,
    
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

    
    val endButtonWidthDp: Int
        get() = if (!isGroup) buttonSizeDp else CallActionBarPlanner.groupEndWidth(buttonSizeDp)

    
    val barHeightDp: Int
        get() = if (layout == ActionBarLayout.TWO_ROW) {
            CallActionBarPlanner.TWO_ROW_HEIGHT_DP
        } else {
            buttonSizeDp
        }

    
    val outsideEmojiReserveDp: Int
        get() = if (emojiInline) 0 else CallActionBarPlanner.OUTSIDE_EMOJI_SIZE_DP + CallActionBarPlanner.OUTSIDE_EMOJI_GAP_DP

    




    val chromeBottomReserveDp: Int
        get() = if (layout == ActionBarLayout.TWO_ROW) {
            
            CallActionBarPlanner.TWO_ROW_PLATE_BOTTOM_DP + CallActionBarPlanner.TWO_ROW_PLATE_PADDING_DP * 2 + barHeightDp
        } else {
            bottomMarginDp + barHeightDp + outsideEmojiReserveDp
        }
}





object CallActionBarPlanner {
    
    const val H_INSET_DP = 16

    const val BUTTON_DP = LCallUiConstants.BOTTOM_BAR_CONTROL_SIZE_DP  
    const val ICON_DP = 24
    const val GAP_DP = 12
    const val COMPACT_BUTTON_DP = 40
    const val COMPACT_ICON_DP = 20
    const val COMPACT_GAP_DP = 8

    
    const val GROUP_END_TAIL_DP = 54
    const val COMPACT_GROUP_END_TAIL_DP = 45

    
    const val SPLIT_SIDE_GAP_DP = 48

    const val OUTSIDE_EMOJI_SIZE_DP = 40
    const val OUTSIDE_EMOJI_ICON_DP = 20
    
    const val OUTSIDE_EMOJI_GAP_DP = 16

    
    const val LABEL_GAP_DP = 8
    const val LABEL_LINE_HEIGHT_DP = 20
    const val LABELLED_BUTTON_HEIGHT_DP = BUTTON_DP + LABEL_GAP_DP + LABEL_LINE_HEIGHT_DP  
    const val TWO_ROW_GAP_DP = 24
    const val TWO_ROW_HEIGHT_DP = LABELLED_BUTTON_HEIGHT_DP * 2 + TWO_ROW_GAP_DP  
    const val TWO_ROW_MAX_WIDTH_DP = 343
    
    const val TWO_ROW_PLATE_PADDING_DP = 16
    const val TWO_ROW_PLATE_BOTTOM_DP = 28

    const val BOTTOM_PORTRAIT_DP = 48
    const val BOTTOM_LANDSCAPE_DP = 20
    const val BOTTOM_TWO_ROW_DP = 44

    





    const val TWO_ROW_MIN_ASPECT = 1.7f

    fun groupEndWidth(buttonSizeDp: Int): Int = if (buttonSizeDp == COMPACT_BUTTON_DP) {
        COMPACT_BUTTON_DP + COMPACT_GROUP_END_TAIL_DP - COMPACT_BUTTON_DP / 2  
    } else {
        BUTTON_DP + GROUP_END_TAIL_DP - BUTTON_DP / 2  
    }

    
    fun rowWidth(count: Int, buttonDp: Int, gapDp: Int): Int = count * buttonDp + (count - 1) * gapDp

    
    fun splitNeed(isGroup: Boolean): Int {
        val mid = if (isGroup) 6 else 5  
        val side = maxOf(BUTTON_DP, if (isGroup) groupEndWidth(BUTTON_DP) else BUTTON_DP)
        return rowWidth(mid, BUTTON_DP, GAP_DP) + side * 2 + SPLIT_SIDE_GAP_DP * 2
    }

    
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
