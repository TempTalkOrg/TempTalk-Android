package com.difft.android.call.ui.actionbar

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.core.CallUiController
import com.difft.android.call.ui.tabletop.LocalCallTabletop
import com.difft.android.call.ui.tabletop.TabletopGeometry
import kotlin.math.ceil





object CallSideRailSpec {
    const val MIN_WINDOW_HEIGHT_DP = 480
    const val START_INSET_DP = 12
    const val END_INSET_DP = 24
    const val BOTTOM_INSET_DP = 24
    const val BUTTON_DP = CallActionBarPlanner.BUTTON_DP  
    const val GAP_DP = CallActionBarPlanner.GAP_DP  
    const val SLOT_COUNT = 6
    const val STACK_HEIGHT_DP = SLOT_COUNT * BUTTON_DP + (SLOT_COUNT - 1) * GAP_DP  

    
    const val MIN_MINIMIZE_GAP_DP = 8

    



    const val HEIGHT_BELOW_TOP_INSET_DP = BUTTON_DP + MIN_MINIMIZE_GAP_DP + STACK_HEIGHT_DP + BOTTOM_INSET_DP  

    
    const val ROOMY_SLOT_COUNT = SLOT_COUNT + 1
    const val ROOMY_STACK_HEIGHT_DP = ROOMY_SLOT_COUNT * BUTTON_DP + (ROOMY_SLOT_COUNT - 1) * GAP_DP  

    




    fun showsRail(widthDp: Float, heightDp: Float): Boolean =
        widthDp > heightDp && heightDp >= MIN_WINDOW_HEIGHT_DP
}






data class SideRail(
    val topInset: Dp = 0.dp,
    val bottomInsetDp: Int = CallSideRailSpec.BOTTOM_INSET_DP,
    val fitsSevenControls: Boolean = false,
) {
    val endInsetDp: Int get() = CallSideRailSpec.END_INSET_DP

    
    val widthDp: Int get() = CallSideRailSpec.START_INSET_DP + CallSideRailSpec.BUTTON_DP + endInsetDp
}







fun resolveSideRail(
    widthDp: Float,
    heightDp: Float,
    statusBandDp: Float = 0f,
    cutouts: List<Rect> = emptyList(),
    isRtl: Boolean = false,
): SideRail? {
    if (!CallSideRailSpec.showsRail(widthDp, heightDp)) return null
    val railWidthDp = (CallSideRailSpec.START_INSET_DP + CallSideRailSpec.BUTTON_DP + CallSideRailSpec.END_INSET_DP).toFloat()
    val clearance = railCutoutClearance(cutouts, widthDp, heightDp, railWidthDp, isRtl)
    val bottomInsetDp = maxOf(CallSideRailSpec.BOTTOM_INSET_DP, ceil(clearance.bottomDp).toInt())
    val minimizeAndBelow = CallSideRailSpec.BUTTON_DP + CallSideRailSpec.MIN_MINIMIZE_GAP_DP + bottomInsetDp
    val wantedTop = maxOf(statusBandDp, clearance.topDp) + LCallUiConstants.TOP_BAR_MARGIN_TOP_DP
    val topDp = wantedTop.coerceAtMost(heightDp - minimizeAndBelow - CallSideRailSpec.STACK_HEIGHT_DP).coerceAtLeast(0f)
    return SideRail(
        topInset = topDp.dp,
        bottomInsetDp = bottomInsetDp,
        fitsSevenControls = heightDp - topDp >= minimizeAndBelow + CallSideRailSpec.ROOMY_STACK_HEIGHT_DP,
    )
}





data class CallChromeLayout(val barPlan: ActionBarPlan, val rail: SideRail?, val dock: TabletopGeometry? = null)


data class CallWindowSizeDp(val width: Float, val height: Float)





@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun rememberCallWindowSizeDp(): CallWindowSizeDp {
    val containerSize = LocalWindowInfo.current.containerSize
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return if (containerSize.width > 0 && containerSize.height > 0) {
        CallWindowSizeDp(containerSize.width / density.density, containerSize.height / density.density)
    } else {
        CallWindowSizeDp(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
    }
}











@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberCallSideRail(): SideRail? {
    
    if (LocalCallTabletop.current != null) return null
    val window = rememberCallWindowSizeDp()
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val statusBandDp = WindowInsets.statusBarsIgnoringVisibility.getTop(density) / density.density
    val cutouts = rememberDisplayCutoutRectsDp()
    return remember(window, statusBandDp, cutouts, isRtl) {
        resolveSideRail(window.width, window.height, statusBandDp, cutouts, isRtl)
    }
}


@Composable
fun rememberCallChromeLayout(isGroup: Boolean): CallChromeLayout =
    CallChromeLayout(rememberCallActionBarPlan(isGroup), rememberCallSideRail(), LocalCallTabletop.current)





@Composable
fun rememberKeepsChromeUnderPanels(): Boolean = rememberCallSideRail() != null || LocalCallTabletop.current != null





fun Modifier.sideRailEndPadding(rail: SideRail?): Modifier =
    if (rail == null) this else this.padding(end = rail.widthDp.dp)


enum class RailSlot { MIC, VIDEO, SPEAKER, EMOJI, PEOPLE, MORE, END }






fun railSlots(isGroup: Boolean, isScreenShare: Boolean, fitsSevenControls: Boolean): List<RailSlot> {
    val groupShare = isGroup && isScreenShare
    return listOfNotNull(
        RailSlot.MIC,
        RailSlot.VIDEO,
        RailSlot.SPEAKER,
        RailSlot.PEOPLE.takeIf { groupShare },
        RailSlot.EMOJI.takeIf { !groupShare || fitsSevenControls },
        RailSlot.MORE,
        RailSlot.END,
    )
}


fun railMoreQuickActions(isGroup: Boolean, isScreenShare: Boolean, fitsSevenControls: Boolean): List<ActionBarQuickAction> =
    when {
        !isGroup -> listOf(ActionBarQuickAction.INVITE)
        !isScreenShare -> listOf(ActionBarQuickAction.INVITE, ActionBarQuickAction.PEOPLE)
        fitsSevenControls -> listOf(ActionBarQuickAction.INVITE)
        else -> listOf(ActionBarQuickAction.INVITE, ActionBarQuickAction.EMOJI)
    }






@Composable
fun rememberRailBandReserved(controller: CallUiController, rail: SideRail?): Boolean {
    if (rail == null) return false
    val visible by rememberChromeVisible(controller, controller.showBottomToolBarViewEnabled)
    return visible
}





fun Modifier.endPadding(end: () -> Dp): Modifier = layout { measurable, constraints ->
    val endPx = end().roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.offset(horizontal = -endPx))
    layout(constraints.constrainWidth(placeable.width + endPx), constraints.constrainHeight(placeable.height)) {
        placeable.placeRelative(0, 0)
    }
}
