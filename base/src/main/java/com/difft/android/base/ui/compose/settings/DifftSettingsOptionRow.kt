package com.difft.android.base.ui.compose.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme
































@Composable
fun DifftSettingsOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val checkmark: (@Composable () -> Unit)? = if (selected) {
        { OptionCheckmark() }
    } else {
        null
    }
    if (description == null) {
        DifftSettingsRow(
            label = label,
            modifier = modifier,
            onClick = onClick,
            showChevron = false,
            trailing = checkmark,
        )
    } else {
        DifftSettingsRow(
            modifier = modifier,
            onClick = onClick,
            showChevron = false,
            trailing = checkmark,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DifftSettingsDefaults.OptionDescriptionGap),
            ) {
                Text(
                    text = label,
                    style = DifftSettingsDefaults.LabelTextStyle,
                    color = DifftTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = DifftSettingsDefaults.OptionDescriptionTextStyle,
                    color = DifftTheme.colors.primary,
                )
            }
        }
    }
}

@Composable
private fun OptionCheckmark() {
    Icon(
        painter = painterResource(R.drawable.base_ic_check),
        contentDescription = null,
        tint = DifftTheme.colors.icon,
        modifier = Modifier
            .size(DifftSettingsDefaults.CheckmarkSize)
            .testTag(DifftSettingsDefaults.CheckmarkTestTag),
    )
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DifftSettingsOptionRowPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(
            Modifier
                .background(DifftTheme.colors.bg)
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup(
                footer = { DifftSettingsCaption("Explains what the group above does.") },
            ) {
                DifftSettingsOptionRow(label = "Default (Off)", selected = true, onClick = {})
                DifftSettingsOptionRow(label = "Always", selected = false, onClick = {})
                DifftSettingsOptionRow(
                    label = "Only when mentioned",
                    selected = false,
                    onClick = {},
                    description = "You will only be notified when someone mentions you.",
                )
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}
