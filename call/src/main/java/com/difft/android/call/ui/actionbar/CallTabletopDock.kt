package com.difft.android.call.ui.actionbar

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.difft.android.call.R
import com.difft.android.call.ui.tabletop.TabletopGeometry


enum class DockSlot { MIC, VIDEO, SPEAKER, EMOJI, INVITE, PEOPLE, MORE, END }






fun dockSlots(isGroup: Boolean, peopleAvailable: Boolean): List<List<DockSlot>> = listOf(
    listOf(DockSlot.MIC, DockSlot.VIDEO, DockSlot.SPEAKER, DockSlot.EMOJI),
    listOfNotNull(DockSlot.INVITE, DockSlot.PEOPLE.takeIf { isGroup && peopleAvailable }, DockSlot.MORE, DockSlot.END),
)





fun moreQuickActions(chrome: CallChromeLayout, isGroup: Boolean, isScreenShare: Boolean): List<ActionBarQuickAction> = when {
    chrome.dock != null -> emptyList()
    chrome.rail != null -> railMoreQuickActions(isGroup, isScreenShare, chrome.rail.fitsSevenControls)
    else -> chrome.barPlan.moreQuickActions
}

private const val DOCK_COLUMNS = 4
private val CONSOLE_PADDING_H = 16.dp
private val CONSOLE_PADDING_TOP = 16.dp
private val CONSOLE_PADDING_BOTTOM = 24.dp
private val CONSOLE_ROW_GAP = 24.dp

internal const val TABLETOP_DOCK_TAG = "call_tabletop_dock"
internal const val TABLETOP_CONSOLE_TAG = "call_tabletop_console"






@Composable
internal fun CallTabletopDock(
    dock: TabletopGeometry,
    rows: List<List<DockSlot>>,
    slots: ActionBarSlots,
    micEnabled: Boolean,
    isGroup: Boolean,
    bottomInsetDp: Float = 0f,
) {
    
    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(dock.dockHeightDp.dp)
                .testTag(TABLETOP_DOCK_TAG),
        )
        
        
        val console = dock.consoleDp(bottomInsetDp)
        BoxWithConstraints(
            Modifier
                .absoluteOffset(x = console.x.toFloat().dp, y = console.y.toFloat().dp)
                .width(console.w.toFloat().dp)
                .height(console.h.toFloat().dp)
                .testTag(TABLETOP_CONSOLE_TAG)
                .semantics { isTraversalGroup = true }
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(start = CONSOLE_PADDING_H, end = CONSOLE_PADDING_H, top = CONSOLE_PADDING_TOP, bottom = CONSOLE_PADDING_BOTTOM),
        ) {
            val cellWidth = maxWidth / DOCK_COLUMNS
            Column(verticalArrangement = Arrangement.spacedBy(CONSOLE_ROW_GAP)) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        row.forEach { slot ->
                            key(slot) {
                                LabeledActionSlot(label = dockLabel(slot, micEnabled, isGroup), modifier = Modifier.width(cellWidth)) {
                                    slots.forDock(slot)()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun ActionBarSlots.forDock(slot: DockSlot): @Composable () -> Unit = when (slot) {
    DockSlot.MIC -> mic
    DockSlot.VIDEO -> video
    DockSlot.SPEAKER -> speaker
    DockSlot.EMOJI -> emoji
    DockSlot.INVITE -> invite
    DockSlot.PEOPLE -> people
    DockSlot.MORE -> more
    DockSlot.END -> end
}

@Composable
private fun dockLabel(slot: DockSlot, micEnabled: Boolean, isGroup: Boolean): String = stringResource(
    id = when (slot) {
        DockSlot.MIC -> if (micEnabled) R.string.call_action_mute else R.string.call_action_unmute
        DockSlot.VIDEO -> R.string.call_action_video
        DockSlot.SPEAKER -> R.string.call_action_speaker
        DockSlot.EMOJI -> R.string.call_action_emoji
        DockSlot.INVITE -> R.string.call_toolbar_bottom_invite_text
        DockSlot.PEOPLE -> R.string.call_action_people
        DockSlot.MORE -> R.string.call_action_more
        DockSlot.END -> if (isGroup) R.string.call_button_group_leave_meeting else R.string.call_action_end
    },
)
