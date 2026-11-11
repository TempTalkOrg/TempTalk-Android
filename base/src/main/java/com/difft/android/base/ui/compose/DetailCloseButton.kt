package com.difft.android.base.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme















@Composable
fun DetailCloseButton(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = source,
            indication = ripple(),
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier.indication(source, ripple())
    }
    Box(
        modifier = modifier
            .size(dimensionResource(R.dimen.detail_close_button_size))
            .floatingSurface(CircleShape, fill = DifftTheme.colors.backgroundTertiary)
            .then(pressModifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.base_ic_close),
            contentDescription = if (onClick != null) stringResource(R.string.base_close) else null,
            tint = DifftTheme.colors.icon,
            modifier = Modifier.size(DifftTheme.spacing.iconMedium),
        )
    }
}
