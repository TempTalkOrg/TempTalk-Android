package com.difft.android.chat.gif.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import kotlin.math.roundToInt

/**
 * Single-row floating menu shown on long-press of a GIF cell (Issue 4): a rounded elevated surface
 * with one row = leading icon + label. It pops up at the finger, like the conversation-list
 * long-press menu.
 *
 * Rendered as an IN-COMPOSITION overlay (a child of the grid's root [Box]), NOT a system
 * [androidx.compose.material3.DropdownMenu]/`Popup`. A Popup lives in its own window, and inside a
 * BottomSheetDialog (the GIF search sheet) with an IME-resized window its anchor coordinates no
 * longer map to screen coordinates, so the menu drifts far from the finger. An in-composition
 * overlay shares the grid's window, so it lands at the finger in both the inline panel and the
 * search sheet.
 *
 * [position] is the press point in the ROOT box's local pixels; [rootSize] is that box's size, used
 * to clamp the menu inside the visible area (so an edge press doesn't push it off-screen). The caller
 * converts the cell-local touch to root-local via window coordinates and owns open/dismiss/action.
 *
 * Browse grids pass the add-to-favorite icon/label; the favorites grid passes the remove icon/label.
 */
@Composable
fun BoxScope.GifFavoriteMenu(
    visible: Boolean,
    position: Offset,
    rootSize: IntSize,
    onDismiss: () -> Unit,
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    onClick: () -> Unit
) {
    if (!visible) return

    // Transparent scrim over the whole grid: a tap outside the menu dismisses it (mirrors a
    // DropdownMenu's outside-tap behaviour) and blocks stray taps on cells beneath.
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    )

    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DifftTheme.colors.backgroundActionPopup,
        shadowElevation = 4.dp,
        modifier = Modifier
            .align(Alignment.TopStart)
            .onSizeChanged { menuSize = it }
            .offset {
                // Clamp so the menu stays fully inside the grid even for edge/bottom presses.
                val maxX = (rootSize.width - menuSize.width).coerceAtLeast(0)
                val maxY = (rootSize.height - menuSize.height).coerceAtLeast(0)
                IntOffset(
                    x = position.x.roundToInt().coerceIn(0, maxX),
                    y = position.y.roundToInt().coerceIn(0, maxY)
                )
            }
    ) {
        // Padding = 16dp horizontal / 10dp vertical per design (a DropdownMenuItem's 48dp min height
        // looks too tall for a single row).
        Row(
            modifier = Modifier
                .clickable {
                    onClick()
                    onDismiss()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = DifftTheme.colors.icon,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(labelRes),
                style = DifftTheme.typography.bodyMedium,
                color = DifftTheme.colors.textPrimary
            )
        }
    }
}
