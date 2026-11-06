package com.difft.android.call.ui.actionbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.R
import com.difft.android.call.ui.tabletop.TabletopGeometry

/**
 * The rail on the window's end edge (#1451): minimize at the top (level with the title bar outside
 * a screen share) and the controls stacked at the bottom. [composed] and [alpha] are the shared
 * fade state, so the toggle, screen-share auto-hide and PiP behave like the bar; open sheets do
 * not fade the rail. Nothing
 * here is clickable except the controls: taps on empty rail area reach the root, which toggles
 * the chrome.
 */
@Composable
fun CallSideRailOverlay(
    rail: SideRail,
    order: List<RailSlot>,
    slots: ActionBarSlots,
    onMinimize: () -> Unit,
    composed: Boolean,
    alpha: () -> Float,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        if (composed) {
            CallSideRail(
                rail = rail,
                order = order,
                slots = slots,
                onMinimize = onMinimize,
                modifier = Modifier.graphicsLayer { this.alpha = alpha() },
            )
        }
    }
}

/**
 * Minimize below [SideRail.topInset], a flexible gap of at least 8dp, then the controls top to
 * bottom in [order], 12dp apart.
 */
@Composable
fun CallSideRail(
    rail: SideRail,
    order: List<RailSlot>,
    slots: ActionBarSlots,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .testTag("call_action_rail")
            .semantics { isTraversalGroup = true }
            .padding(
                top = rail.topInset,
                start = CallSideRailSpec.START_INSET_DP.dp,
                end = rail.endInsetDp.dp,
                bottom = rail.bottomInsetDp.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailMinimizeButton(onClick = onMinimize)
        Spacer(Modifier.height(CallSideRailSpec.MIN_MINIMIZE_GAP_DP.dp))
        Spacer(Modifier.weight(1f))
        Column(
            verticalArrangement = Arrangement.spacedBy(CallSideRailSpec.GAP_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            order.forEach { slot ->
                key(slot) { slots.forRail(slot)() }
            }
        }
    }
}

private fun ActionBarSlots.forRail(slot: RailSlot): @Composable () -> Unit = when (slot) {
    RailSlot.MIC -> mic
    RailSlot.VIDEO -> video
    RailSlot.SPEAKER -> speaker
    RailSlot.EMOJI -> emoji
    RailSlot.PEOPLE -> people
    RailSlot.MORE -> more
    RailSlot.END -> end
}

/** Minimize on the rail: a regular rail control; the title bar drops its own while the rail shows. */
@Composable
fun RailMinimizeButton(onClick: () -> Unit) {
    CallActionButton(
        painter = painterResource(id = R.drawable.chat_ic_window_zoom_out),
        contentDescription = "WINDOW_ZOOM_OUT",
        iconSize = CallActionBarPlanner.ICON_DP.dp,
        testTag = "call_rail_zoom_out",
        onClick = onClick,
    )
}

/**
 * Group End on the rail and the tabletop dock: one red control that opens the end menu (end for
 * all / leave / cancel). Neither has room for the bar's leave + chevron split control.
 */
@Composable
fun RailGroupEndButton(onShowEndMenu: () -> Unit, size: Dp = CallSideRailSpec.BUTTON_DP.dp) {
    CallActionButton(
        painter = painterResource(id = R.drawable.call_ic_hangup),
        contentDescription = stringResource(id = R.string.call_action_end),
        style = ActionButtonStyle.END,
        size = size,
        testTag = "call_btn_rail_end",
        onClick = {
            L.i { "[Call] onClick railEnd" }
            onShowEndMenu()
        },
    )
}

/**
 * One line on first composition, on each switch between the bottom bar, the rail and the tabletop
 * dock, and when the rail's top or bottom inset or its seven-control fit changes, with the window
 * size (whole dp).
 */
@Composable
fun ControlsPlacementLog(rail: SideRail?, isGroup: Boolean, dock: TabletopGeometry? = null) {
    val window = rememberCallWindowSizeDp()
    LaunchedEffect(rail, dock) {
        val onRail = rail != null
        val placement = when {
            dock != null -> "tabletop"
            onRail -> "rail"
            else -> "bar"
        }
        val widthDp = window.width.toInt()
        val heightDp = window.height.toInt()
        val topDp = rail?.topInset?.value?.toInt()
        val bottomDp = rail?.bottomInsetDp
        val fitsSeven = rail?.fitsSevenControls
        L.i {
            "[Call] controlsPlacement placement=$placement rail=$onRail widthDp=$widthDp heightDp=$heightDp group=$isGroup " +
                "railTopDp=$topDp railBottomDp=$bottomDp fitsSeven=$fitsSeven"
        }
    }
}
