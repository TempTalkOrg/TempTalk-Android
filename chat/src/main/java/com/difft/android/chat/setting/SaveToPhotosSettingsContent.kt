package com.difft.android.chat.setting

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsOptionRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R

/**
 * The three conversation-level save-to-photos values, in display order. `null` means the
 * conversation follows the global setting, `1` always saves, `0` never does.
 */
private val SaveToPhotosOptions: List<Int?> = listOf(null, 1, 0)

/** Label for an explicit option; the `null` option's label is dynamic and comes from the host. */
private fun saveToPhotosLabelRes(value: Int): Int = if (value == 1) {
    R.string.save_to_photos_always
} else {
    R.string.save_to_photos_never
}

/**
 * Stateless body of the conversation save-to-photos screen.
 *
 * [selected] is the stored value, so `null` is a real selection ("follow the global setting") and
 * not "nothing selected" — the row comparison must stay value equality, never a null check.
 * [defaultLabel] carries the global on/off state the host resolved, which this body cannot read.
 */
@Composable
fun SaveToPhotosSettingsContent(
    selected: Int?,
    defaultLabel: String,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        DifftSettingsGroup(
            footer = { DifftSettingsCaption(stringResource(R.string.me_automatically_save_photos)) },
        ) {
            SaveToPhotosOptions.forEach { value ->
                DifftSettingsOptionRow(
                    label = if (value == null) defaultLabel else stringResource(saveToPhotosLabelRes(value)),
                    selected = selected == value,
                    onClick = { onSelect(value) },
                )
            }
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SaveToPhotosSettingsContentPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 420.dp)) {
            SaveToPhotosSettingsContent(
                selected = null,
                defaultLabel = "Default (Off)",
                onSelect = {},
            )
        }
    }
}
