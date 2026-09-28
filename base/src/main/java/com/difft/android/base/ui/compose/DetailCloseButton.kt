package com.difft.android.base.ui.compose

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Floating round close control anchored bottom-centre on message detail pages (large-text viewer,
 * media preview, confidential sheets): a 48dp `bg3` disc with a drop shadow and a 24dp
 * `icon`-tinted cross, mirroring the iOS detail close button.
 *
 * Geometry lives in `dimens.xml` (`detail_close_button_size` / `_margin` / `_elevation` /
 * `_content_inset`) so View layouts and Compose callers read the same values; a scrolling content
 * under the button pads its bottom by `detail_close_button_content_inset`.
 *
 * A non-null [onClick] makes the disc clickable with button semantics; `null` renders the visual
 * only, for a View shell that owns the click and the accessibility node itself
 * ([com.difft.android.base.widget.DetailCloseButtonView]). Either way the press indication draws
 * from [interactionSource], so a View-owned click still shows its ripple. Pass [elevation] `0.dp`
 * when the host draws the shadow, as the shell does: a Compose shadow is clipped to the
 * composition bounds.
 */
@Composable
fun DetailCloseButton(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    elevation: Dp = dimensionResource(R.dimen.detail_close_button_elevation),
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
            .shadow(elevation, CircleShape)
            .clip(CircleShape)
            .background(DifftTheme.colors.backgroundTertiary)
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
