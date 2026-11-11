package com.difft.android.chat.gif.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuItem
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuItemColors
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuPanel
import kotlin.math.roundToInt
import com.difft.android.chat.ui.messageaction.MessageActionMenuColors




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

    
    
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    )

    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    DifftDropdownMenuPanel(
        color = MessageActionMenuColors.panel,
        modifier = Modifier
            .align(Alignment.TopStart)
            .onSizeChanged { menuSize = it }
            .offset {
                
                val maxX = (rootSize.width - menuSize.width).coerceAtLeast(0)
                val maxY = (rootSize.height - menuSize.height).coerceAtLeast(0)
                IntOffset(
                    x = position.x.roundToInt().coerceIn(0, maxX),
                    y = position.y.roundToInt().coerceIn(0, maxY)
                )
            }
    ) {
        DifftDropdownMenuItem(
            text = stringResource(labelRes),
            iconRes = iconRes,
            colors = DifftDropdownMenuItemColors(
                content = MessageActionMenuColors.contentDefault,
                pressedPill = MessageActionMenuColors.pressedPill,
            ),
            onClick = {
                onClick()
                onDismiss()
            }
        )
    }
}
