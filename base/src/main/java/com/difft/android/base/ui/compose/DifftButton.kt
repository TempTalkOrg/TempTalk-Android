package com.difft.android.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow























@Composable
fun DifftButton(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: DifftButtonSize = DifftButtonSize.XL,
    variant: DifftButtonVariant = DifftButtonVariant.Primary,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = DifftButtonDefaults.shape(size, variant)
    
    val presses = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    
    
    val clickGate = remember { DifftButtonDefaults.ClickGate() }
    val interaction = if (onClick != null) {
        Modifier.clickable(
            interactionSource = presses,
            indication = null,
            enabled = enabled,
            role = Role.Button,
        ) {
            if (clickGate.tryAccept()) onClick()
        }
    } else {
        Modifier
    }
    val washed = enabled && pressed && DifftButtonDefaults.hasPressOverlay(variant)
    Row(
        modifier = modifier
            .then(
                if (!enabled && DifftButtonDefaults.dimsWhenDisabled(variant)) {
                    Modifier.alpha(DifftButtonDefaults.DisabledAlpha)
                } else {
                    Modifier
                }
            )
            .defaultMinSize(
                minWidth = DifftButtonDefaults.minWidth(size),
                minHeight = DifftButtonDefaults.height(size),
            )
            
            
            .clip(shape)
            .background(DifftButtonDefaults.containerColor(variant, enabled, pressed), shape)
            .then(if (washed) Modifier.background(DifftButtonDefaults.PressOverlay, shape) else Modifier)
            .then(
                if (DifftButtonDefaults.isOutlined(variant)) {
                    Modifier.border(DifftButtonDefaults.BorderWidth, DifftButtonDefaults.borderColor(), shape)
                } else {
                    Modifier
                }
            )
            .then(interaction)
            .padding(DifftButtonDefaults.contentPadding(size)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides DifftButtonDefaults.contentColor(variant, enabled),
        ) {
            content()
        }
    }
}





@Composable
fun DifftButton(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: DifftButtonSize = DifftButtonSize.XL,
    variant: DifftButtonVariant = DifftButtonVariant.Primary,
    interactionSource: MutableInteractionSource? = null,
) = DifftButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    size = size,
    variant = variant,
    interactionSource = interactionSource,
) {
    Text(
        text = text,
        style = DifftButtonDefaults.textStyle(size),
        color = DifftButtonDefaults.contentColor(variant, enabled),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}
