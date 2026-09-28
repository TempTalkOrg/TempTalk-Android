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

/**
 * The app's button (Wea Design system, page `Button`). One [size] picks the height, padding,
 * corner radius and type-scale step; [variant] picks the emphasis. There is no loading state — a
 * button that starts an asynchronous operation shows the global wait dialog instead, see
 * [com.difft.android.base.widget.withWait].
 *
 * A null [onClick] renders the visual only, for the [com.difft.android.base.widget.DifftButtonView]
 * shell, which owns the click and the accessibility node at the View level (same inversion as
 * [DifftSwitch] / [DifftCheckBox]). The shell passes the press source its View feeds as
 * [interactionSource], so the button still shows its pressed state for a click it never handles;
 * with a non-null [onClick] the parameter is simply the clickable's own source.
 *
 * Press feedback follows the design rather than Material: a filled variant darkens under a black
 * wash, Secondary fills `backgroundTertiary` and Text fills `backgroundBlue`. There is no ripple.
 *
 * Sizing: the size's height is a *minimum*, not a fixed height, so an explicit outer size (a
 * `Modifier.height`, or the EXACTLY constraints an XML `layout_height` produces) wins and the
 * background fills that box, while an unconstrained caller gets exactly the design height. Width
 * has a per-size minimum and no maximum, so a full-width caller adds `fillMaxWidth()`.
 *
 * A second click within [DifftButtonDefaults.ClickDebounceMillis] is dropped.
 */
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
    // A source is always needed: the pressed state, not an Indication, paints the press.
    val presses = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    // Plain object rather than state: the deadline drives nothing on screen, so writing it must
    // not recompose the button.
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
            // clip before the click so the press stops at the corner radius; the click before the
            // padding so the padded area is part of the touch target.
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

/**
 * Text form — the shape every call site uses. A CTA label is single line with an ellipsis: it must
 * never reflow the button's height, which the size owns.
 */
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
