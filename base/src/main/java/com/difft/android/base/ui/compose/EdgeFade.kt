package com.difft.android.base.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.difft.android.base.ui.theme.DifftTheme


@Composable
fun Modifier.edgeFade(canScrollBackward: Boolean, canScrollForward: Boolean): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val left = if (rtl) canScrollForward else canScrollBackward
    val right = if (rtl) canScrollBackward else canScrollForward
    val width = DifftTheme.contacts.edgeFade
    val transparent = DifftTheme.contacts.transparent
    val opaque = DifftTheme.contacts.countdownForeground
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width / 2)
        if (left) drawRect(
            brush = Brush.horizontalGradient(listOf(transparent, opaque), endX = fade),
            size = Size(fade, size.height), blendMode = BlendMode.DstIn,
        )
        if (right) drawRect(
            brush = Brush.horizontalGradient(listOf(opaque, transparent), startX = size.width - fade, endX = size.width),
            topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height), blendMode = BlendMode.DstIn,
        )
    }
}
