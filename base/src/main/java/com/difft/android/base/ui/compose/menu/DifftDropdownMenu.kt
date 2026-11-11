package com.difft.android.base.ui.compose.menu

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.difft.android.base.R
import com.difft.android.base.ui.compose.floatingMenuEntrance
import com.difft.android.base.ui.compose.floatingSurface
import com.difft.android.base.ui.theme.DifftRadius
import com.difft.android.base.ui.theme.DifftTheme




object DifftDropdownMenuDefaults {
    
    val PanelShape: Shape = RoundedCornerShape(DifftRadius.floating)

    
    val PanelElevation: Dp = 4.dp

    val MinWidth: Dp = 180.dp
    val MaxWidth: Dp = 280.dp

    
    val PanelVerticalPadding: Dp = 4.dp

    
    val ItemHeight: Dp = 44.dp
    val ItemHorizontalPadding: Dp = 16.dp
    val ItemVerticalPadding: Dp = 10.dp
    val IconSize: Dp = 20.dp
    val IconGap: Dp = 8.dp
    val TrailingIconSize: Dp = 16.dp

    
    val DividerHeight: Dp = 8.dp
    val dividerThickness: Dp
        @Composable
        @ReadOnlyComposable
        get() = DifftTheme.hairline

    
    internal val PressedPillHorizontalInset: Dp = 4.dp
    internal val PressedPillVerticalInset: Dp = 2.dp
    internal val PressedPillShape: Shape = DifftRadius.mediumShape

    
    val panelColor: Color
        @Composable @ReadOnlyComposable
        get() = if (DifftTheme.colors.isDark) {
            DifftTheme.colors.backgroundModal
        } else {
            DifftTheme.colors.backgroundPopup
        }

    
    internal val pressedPillColor: Color
        @Composable @ReadOnlyComposable
        get() = DifftTheme.colors.fill
}















@Composable
fun DifftDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .widthIn(
                min = DifftDropdownMenuDefaults.MinWidth,
                max = DifftDropdownMenuDefaults.MaxWidth,
            )
            .then(modifier),
        offset = offset,
        properties = properties,
        shape = DifftDropdownMenuDefaults.PanelShape,
        containerColor = DifftDropdownMenuDefaults.panelColor,
        tonalElevation = 0.dp,
        shadowElevation = DifftDropdownMenuDefaults.PanelElevation,
        content = content,
    )
}












@Composable
fun DifftDropdownMenuPanel(
    modifier: Modifier = Modifier,
    color: Color = DifftDropdownMenuDefaults.panelColor,
    entranceOrigin: TransformOrigin = TransformOrigin(0f, 0f),
    content: @Composable ColumnScope.() -> Unit,
) {
    
    Box(
        modifier = modifier
            .floatingMenuEntrance(entranceOrigin)
            .floatingSurface(DifftDropdownMenuDefaults.PanelShape, fill = color)
            .clip(DifftDropdownMenuDefaults.PanelShape),
    ) {
        Column(
            
            
            modifier = Modifier
                .widthIn(
                    min = DifftDropdownMenuDefaults.MinWidth,
                    max = DifftDropdownMenuDefaults.MaxWidth,
                )
                .width(IntrinsicSize.Max)
                .padding(vertical = DifftDropdownMenuDefaults.PanelVerticalPadding),
            content = content,
        )
    }
}


enum class DifftDropdownMenuItemTone {
    
    Default,

    
    Destructive,

    
    Selected;

    companion object {
        
        fun selection(selected: Boolean): DifftDropdownMenuItemTone = if (selected) Selected else Default
    }
}





@Immutable
data class DifftDropdownMenuItemColors(val content: Color, val pressedPill: Color)










@Composable
fun DifftDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    enabled: Boolean = true,
    tone: DifftDropdownMenuItemTone = DifftDropdownMenuItemTone.Default,
    colors: DifftDropdownMenuItemColors? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val labelColor = when {
        !enabled -> DifftTheme.colors.textDisabled
        colors != null -> colors.content
        tone == DifftDropdownMenuItemTone.Destructive -> DifftTheme.colors.textError
        tone == DifftDropdownMenuItemTone.Selected -> DifftTheme.colors.primary
        else -> DifftTheme.colors.textPrimary
    }
    val iconTint = when {
        !enabled -> DifftTheme.colors.textDisabled
        colors != null -> colors.content
        tone == DifftDropdownMenuItemTone.Destructive -> DifftTheme.colors.textError
        tone == DifftDropdownMenuItemTone.Selected -> DifftTheme.colors.primary
        else -> DifftTheme.colors.icon
    }

    val defaultPillColor = DifftDropdownMenuDefaults.pressedPillColor
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pillColor = when {
        !pressed || !enabled -> Color.Transparent
        colors != null -> colors.pressedPill
        else -> defaultPillColor
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DifftDropdownMenuDefaults.ItemHeight)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(
                horizontal = DifftDropdownMenuDefaults.PressedPillHorizontalInset,
                vertical = DifftDropdownMenuDefaults.PressedPillVerticalInset,
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(pillColor, DifftDropdownMenuDefaults.PressedPillShape)
                .padding(
                    horizontal = DifftDropdownMenuDefaults.ItemHorizontalPadding -
                        DifftDropdownMenuDefaults.PressedPillHorizontalInset
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DifftDropdownMenuDefaults.IconGap),
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(DifftDropdownMenuDefaults.IconSize),
                )
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = DifftTheme.typography.p2,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                trailing != null -> trailing()
                tone == DifftDropdownMenuItemTone.Selected -> Icon(
                    painter = painterResource(R.drawable.base_ic_check),
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(DifftDropdownMenuDefaults.TrailingIconSize),
                )
            }
        }
    }
}


@Composable
fun DifftDropdownMenuDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DifftDropdownMenuDefaults.DividerHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DifftDropdownMenuDefaults.dividerThickness)
                .background(DifftTheme.colors.dividerFloating)
        )
    }
}
