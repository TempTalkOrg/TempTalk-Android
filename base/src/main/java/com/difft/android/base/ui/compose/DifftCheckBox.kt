package com.difft.android.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme

/** Figma multi-select checkbox geometry. The View shell [com.difft.android.base.widget.DifftCheckBoxView] reuses it. */
object DifftCheckBoxDefaults {
    val Size: Dp = 16.dp
    val CornerRadius: Dp = 2.dp
    val BorderWidth: Dp = 1.dp
    val BoxShape: Shape = RoundedCornerShape(CornerRadius)

    /** Wrap size of the AppCompat checkbox this replaces; keeps existing layouts from shifting. */
    val TouchTarget: Dp = 32.dp
}

/**
 * The app's multi-select checkbox (Figma `check`, 16dp with 2dp corners): a 1dp `textDisabled`
 * border when unchecked, a `primary` fill with an on-primary filled checkmark when checked. The
 * unchecked box is transparent rather than the spec's `bg1` fill: on a `bg1` row the two are
 * indistinguishable, while on other surfaces (popup sheets, the forced-dark invite row) a filled
 * box would read as a darker square inside its border.
 *
 * Mirrors the Material3 `Checkbox` parameter shape so call sites migrate by renaming. A non-null
 * [onCheckedChange] makes the box toggleable in its own right with a 48dp touch target and
 * checkbox semantics; `null` renders the visual only, for rows that toggle on the row click and
 * carry the semantics themselves.
 *
 * Disabled states are not in Figma; they follow the pre-existing call treatment: `backgroundDisabled`
 * fill with a `textDisabled` glyph when checked, a `backgroundDisabled` border when unchecked.
 */
@Composable
fun DifftCheckBox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fill = when {
        !checked -> Color.Transparent
        enabled -> DifftTheme.colors.primary
        else -> DifftTheme.colors.backgroundDisabled
    }
    val border = when {
        checked -> null
        enabled -> DifftTheme.colors.textDisabled
        else -> DifftTheme.colors.backgroundDisabled
    }
    val glyph = if (enabled) DifftTheme.colors.iconOnPrimary else DifftTheme.colors.textDisabled

    val interaction = if (onCheckedChange != null) {
        Modifier
            .defaultMinSize(DifftTheme.spacing.minTouchTarget, DifftTheme.spacing.minTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
    } else {
        Modifier
    }
    Box(modifier = modifier.then(interaction), contentAlignment = Alignment.Center) {
        val boxModifier = Modifier
            .size(DifftCheckBoxDefaults.Size)
            .clip(DifftCheckBoxDefaults.BoxShape)
            .background(fill)
        Box(
            modifier = if (border != null) {
                boxModifier.border(DifftCheckBoxDefaults.BorderWidth, border, DifftCheckBoxDefaults.BoxShape)
            } else {
                boxModifier
            },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    painter = painterResource(R.drawable.base_ic_checkmark),
                    contentDescription = null,
                    tint = glyph,
                    modifier = Modifier.size(DifftCheckBoxDefaults.Size),
                )
            }
        }
    }
}
