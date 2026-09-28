package com.difft.android.setting.language

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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsOptionRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.LanguageData
import java.util.Locale

/**
 * Stateless body of the language screen.
 *
 * The selected row comes from [LanguageData.selected], which `LanguageUtils.getLanguageList`
 * resolves by language code. Taps report the entry itself; the host decides via
 * `LanguageUtils.isCurrentChoice` whether a restart is needed.
 */
@Composable
fun LanguageSettingsContent(
    languages: List<LanguageData>,
    onSelect: (LanguageData) -> Unit,
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
            languages.forEach { language ->
                DifftSettingsOptionRow(
                    label = language.name,
                    selected = language.selected,
                    onClick = { onSelect(language) },
                )
            }
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LanguageSettingsContentPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 220.dp)) {
            LanguageSettingsContent(
                languages = listOf(
                    LanguageData("English", Locale.US, selected = true),
                    LanguageData("中文", Locale.CHINA),
                ),
                onSelect = {},
            )
        }
    }
}
