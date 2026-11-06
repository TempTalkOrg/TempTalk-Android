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

/** Controls on the tabletop dock. */
enum class DockSlot { MIC, VIDEO, SPEAKER, EMOJI, INVITE, PEOPLE, MORE, END }

/**
 * Dock rows, top to bottom. Group: Mic · Video · Speaker · Emoji over Invite · People · More ·
 * Leave, People only where the participants panel can render ([peopleAvailable]). 1v1 has no
 * panel: Invite · More · End (flip camera stays in the More sheet, so the grid never jumps).
 */
fun dockSlots(isGroup: Boolean, peopleAvailable: Boolean): List<List<DockSlot>> = listOf(
    listOf(DockSlot.MIC, DockSlot.VIDEO, DockSlot.SPEAKER, DockSlot.EMOJI),
    listOfNotNull(DockSlot.INVITE, DockSlot.PEOPLE.takeIf { isGroup && peopleAvailable }, DockSlot.MORE, DockSlot.END),
)

/**
 * More-sheet quick actions for [chrome]: none on the tabletop dock (Invite and Emoji are on it, People
 * only for group calls when the panel is available), the rail's own set beside the rail (#1451), otherwise what the bar plan dropped.
 */
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

/**
 * The controls below a half-open horizontal fold, always shown while in tabletop. The dock box is
 * transparent and takes no input, so the page ground shows through and the 1v1 floating window
 * above or beside the console stays interactive; a tap there reaches the root like a tap on the
 * stage. Only the console, where the layout put it at the window bottom (call-layout decision 39,
 * see [TabletopGeometry.consoleDp]), consumes taps, so taps between its controls do not toggle the
 * title bar. Provisional style after the iOS standing console, to be refined by the UI design.
 */
@Composable
internal fun CallTabletopDock(
    dock: TabletopGeometry,
    rows: List<List<DockSlot>>,
    slots: ActionBarSlots,
    micEnabled: Boolean,
    isGroup: Boolean,
    bottomInsetDp: Float = 0f,
) {
    // Top left, never mirrored: the console's absolute offset is from the window's left edge.
    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(dock.dockHeightDp.dp)
                .testTag(TABLETOP_DOCK_TAG),
        )
        // In window dp, like the cards: never mirrored in RTL, and outside the dock box so a console
        // laid out above the dock top still takes its taps.
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
