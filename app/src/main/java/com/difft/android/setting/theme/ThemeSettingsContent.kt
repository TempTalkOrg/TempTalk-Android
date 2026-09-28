package com.difft.android.setting.theme

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
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
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsOptionRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R

/** The three night modes this page offers, in the order they are listed. */
private val ThemeModes = listOf(
    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    AppCompatDelegate.MODE_NIGHT_NO,
    AppCompatDelegate.MODE_NIGHT_YES,
)

/** Label only. */
private fun themeLabelRes(mode: Int): Int = when (mode) {
    AppCompatDelegate.MODE_NIGHT_NO -> R.string.me_theme_light
    AppCompatDelegate.MODE_NIGHT_YES -> R.string.me_theme_dark
    else -> R.string.me_theme_system
}

/**
 * Stateless body of the theme screen.
 *
 * Every tap reports through [onSelect], including a tap on the already-selected row: applying the
 * same night mode is a no-op, so no guard is needed here.
 *
 * A [selectedMode] outside [ThemeModes] leaves every row unmarked; selection is exact equality.
 */
@Composable
fun ThemeSettingsContent(
    selectedMode: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        DifftSettingsGroup {
            ThemeModes.forEach { mode ->
                DifftSettingsOptionRow(
                    label = stringResource(themeLabelRes(mode)),
                    selected = selectedMode == mode,
                    onClick = { onSelect(mode) },
                )
            }
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ThemeSettingsContentPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 260.dp)) {
            ThemeSettingsContent(
                selectedMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                onSelect = {},
            )
        }
    }
}
