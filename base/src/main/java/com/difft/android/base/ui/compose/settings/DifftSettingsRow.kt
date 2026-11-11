package com.difft.android.base.ui.compose.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme











@Composable
fun DifftSettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
    labelInfo: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Dp = DifftSettingsDefaults.RowMinHeight,
    contentPadding: PaddingValues = DifftSettingsDefaults.RowContentPadding,
) = DifftSettingsRow(
    modifier = modifier,
    onClick = onClick,
    enabled = enabled,
    showChevron = showChevron,
    leading = leading,
    trailing = trailing,
    minHeight = minHeight,
    contentPadding = contentPadding,
) {
    
    
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = DifftSettingsDefaults.LabelTextStyle,
            color = if (enabled) DifftTheme.colors.textPrimary else DifftTheme.colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (labelInfo != null) {
            Spacer(Modifier.width(DifftSettingsDefaults.LabelInfoGap))
            labelInfo()
        }
    }
    if (value != null) {
        Spacer(Modifier.width(DifftSettingsDefaults.TrailingGap))
        Text(
            text = value,
            style = DifftSettingsDefaults.LabelTextStyle,
            color = if (enabled) DifftTheme.colors.textTertiary else DifftTheme.colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}










@Composable
fun DifftSettingsRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Dp = DifftSettingsDefaults.RowMinHeight,
    contentPadding: PaddingValues = DifftSettingsDefaults.RowContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            
            
            .background(DifftSettingsDefaults.RowSurfaceColor)
            .then(interaction)
            .defaultMinSize(minHeight = minHeight)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(DifftSettingsDefaults.LeadingGap))
        }
        content()
        when {
            trailing != null -> {
                Spacer(Modifier.width(DifftSettingsDefaults.TrailingGap))
                trailing()
            }

            showChevron -> {
                Spacer(Modifier.width(DifftSettingsDefaults.TrailingGap))
                RowChevron(enabled = enabled)
            }
        }
    }
}

@Composable
private fun RowChevron(enabled: Boolean) {
    
    
    Icon(
        painter = painterResource(R.drawable.base_ic_chevron_right),
        contentDescription = null,
        tint = if (enabled) DifftTheme.colors.icon else DifftTheme.colors.textDisabled,
        modifier = Modifier
            .size(DifftSettingsDefaults.ChevronSize)
            .testTag(DifftSettingsDefaults.ChevronTestTag),
    )
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DifftSettingsRowPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(
            Modifier
                .background(DifftTheme.colors.bg)
                .padding(DifftSettingsDefaults.PageInset),
        ) {
            DifftSettingsGroup {
                DifftSettingsRow(label = "Plain label")
                DifftSettingsRow(label = "Label with value", value = "Value")
                DifftSettingsRow(label = "Clickable", onClick = {})
                DifftSettingsRow(
                    label = "Custom trailing",
                    onClick = {},
                    trailing = {
                        Text(
                            text = "Refresh",
                            style = DifftSettingsDefaults.CaptionTextStyle,
                            color = DifftTheme.colors.primary,
                        )
                    },
                )
                DifftSettingsRow(
                    onClick = {},
                    showChevron = false,
                    leading = {
                        Spacer(
                            Modifier
                                .size(DifftSettingsDefaults.LeadingDotSize)
                                .background(DifftTheme.colors.success, DifftSettingsDefaults.GroupShape),
                        )
                    },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Composed row",
                            style = DifftSettingsDefaults.LabelTextStyle,
                            color = DifftTheme.colors.textPrimary,
                        )
                        Text(
                            text = "second line",
                            style = DifftSettingsDefaults.CaptionTextStyle,
                            color = DifftTheme.colors.textTertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}
