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

/**
 * Single-select settings cell (Figma `12405:30459`): a label, an optional explanation line, and a
 * 24dp checkmark on the trailing edge while [selected].
 *
 * Selection is the caller's: the row renders [selected] and reports taps, it holds no state and
 * never compares values itself, so a page keeps its own equality rule (nullable Int, Float, enum).
 *
 * No placeholder occupies the trailing edge while unselected, and none is needed: the label column
 * claims the free space with `weight(1f)`, so it does not shift, and 10 + 24 + 10 stays inside the
 * 52dp row minimum, so the row does not change height either.
 *
 * A [description] switches the row to the composed form (label column of two lines); the row then
 * grows past 52dp instead of clipping. The label is always a single line (ellipsized), the
 * description wraps.
 *
 * The checkmark is decorative (`contentDescription = null`): selection is conveyed by the label the
 * user just tapped, and this kit carries no accessibility semantics (project decision).
 *
 * Inside a [DifftSettingsGroup] no divider is passed — the group produces the hairlines:
 * ```
 * DifftSettingsGroup(footer = { DifftSettingsCaption(explanation) }) {
 *     options.forEach { option ->
 *         DifftSettingsOptionRow(
 *             label = labelOf(option),
 *             selected = option == current,
 *             onClick = { onSelect(option) },
 *         )
 *     }
 * }
 * ```
 */
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
                    color = DifftTheme.colors.textInfo,
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
