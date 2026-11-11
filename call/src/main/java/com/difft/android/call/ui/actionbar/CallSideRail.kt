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
