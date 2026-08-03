package com.difft.android.linkeddevices

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
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import kotlin.math.roundToInt

/**
 * Single-row destructive floating menu shown on long-press of a device row. In-composition overlay
 * (not a system Popup) so it lands at the finger with no window drift. [position] is the press point
 * in the root box's local pixels; [rootSize] clamps the menu inside the visible area.
 */
@Composable
fun BoxScope.LinkedDeviceUnlinkMenu(
    position: Offset,
    rootSize: IntSize,
    onDismiss: () -> Unit,
    onUnlink: () -> Unit,
) {
    // Transparent scrim: a tap outside dismisses and blocks stray taps beneath.
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
                val maxX = (rootSize.width - menuSize.width).coerceAtLeast(0)
                val maxY = (rootSize.height - menuSize.height).coerceAtLeast(0)
                IntOffset(
                    x = position.x.roundToInt().coerceIn(0, maxX),
                    y = position.y.roundToInt().coerceIn(0, maxY),
                )
            }
    ) {
        Row(
            modifier = Modifier
                .clickable {
                    onUnlink()
                    onDismiss()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(com.difft.android.chat.R.drawable.ic_trash_24),
                contentDescription = null,
                tint = DifftTheme.colors.error,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.linked_devices_unlink_action),
                style = DifftTheme.typography.bodyMedium,
                color = DifftTheme.colors.error,
            )
        }
    }
}
