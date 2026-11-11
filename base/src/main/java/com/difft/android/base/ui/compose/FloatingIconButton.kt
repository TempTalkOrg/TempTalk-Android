package com.difft.android.base.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme







@Composable
fun FloatingIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = DifftTheme.colors.icon,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .cornerCutoutPadding()
            .size(FloatingTopBarButtonSize)
            .floatingPill()
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}






@Composable
fun FloatingTextButton(
    text: String,
    onClick: () -> Unit,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .cornerCutoutPadding()
            .height(FloatingTopBarButtonSize)
            .widthIn(min = FloatingTopBarButtonSize)
            .floatingSurface(cornerRadius = FloatingTopBarButtonSize / 2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FloatingTextButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = style, color = color, maxLines = 1)
    }
}


val FloatingTopBarButtonSize = 44.dp

private val FloatingTextButtonPadding = 16.dp


val FloatingIconButtonEdge = 16.dp


val FloatingIconButtonTitleGap = 12.dp
