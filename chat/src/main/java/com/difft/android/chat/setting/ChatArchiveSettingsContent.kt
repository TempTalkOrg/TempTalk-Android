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
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText

/**
 * Stateless body of the disappearing-messages screen.
 *
 * [options] is the server-configured list as it stands. A conversation may already carry a value
 * the configuration no longer offers (an older option, or one set from another client), so such a
 * [selected] value is appended as the last row instead of leaving the screen with no selection.
 */
@Composable
fun ChatArchiveSettingsContent(
    options: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = if (selected in options) options else options + selected
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        DifftSettingsGroup(
            footer = { DifftSettingsCaption(stringResource(R.string.disappearing_messages_tips)) },
        ) {
            rows.forEach { option ->
                DifftSettingsOptionRow(
                    label = option.toArchiveTimeDisplayText(),
                    selected = selected == option,
                    onClick = { onSelect(option) },
                )
            }
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatArchiveSettingsContentPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 420.dp)) {
            ChatArchiveSettingsContent(
                options = listOf(0L, 86_400L, 604_800L),
                selected = 86_400L,
                onSelect = {},
            )
        }
    }
}
