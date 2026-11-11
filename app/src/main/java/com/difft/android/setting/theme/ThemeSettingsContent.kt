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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.sidechrome.NavigationPlacement
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.LocalDifftTypeScale
import com.difft.android.base.ui.theme.scaled
import com.difft.android.base.utils.density.DisplaySize
import com.difft.android.chat.R
import com.difft.android.chat.ui.theme.ChatTheme


private val ThemeModes = listOf(
    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    AppCompatDelegate.MODE_NIGHT_NO,
    AppCompatDelegate.MODE_NIGHT_YES,
)


internal const val LARGE_TEXT_SCALE = 1.5f


private fun themeLabelRes(mode: Int): Int = when (mode) {
    AppCompatDelegate.MODE_NIGHT_NO -> R.string.me_theme_light
    AppCompatDelegate.MODE_NIGHT_YES -> R.string.me_theme_dark
    else -> R.string.me_theme_system
}













@Composable
fun ThemeSettingsContent(
    selectedMode: Int,
    onSelect: (Int) -> Unit,
    chatTheme: ChatTheme,
    onSelectChatTheme: (ChatTheme) -> Unit,
    largeText: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    displaySize: DisplaySize,
    onDisplaySizeChange: (DisplaySize) -> Unit,
    navigationPlacement: NavigationPlacement?,
    onNavigationPlacementChange: (NavigationPlacement) -> Unit,
    statusBarHidden: Boolean,
    onStatusBarHiddenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseScale = LocalDifftTypeScale.current
    val typeScale = remember(baseScale, largeText) { if (largeText) baseScale.scaled(LARGE_TEXT_SCALE) else baseScale }
    CompositionLocalProvider(LocalDifftTypeScale provides typeScale) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            ThemeOptionSection(
                title = stringResource(R.string.me_color_mode),
                note = stringResource(R.string.me_color_mode_note),
                thumbnailHeight = AppearanceThumbnailHeight,
                options = ThemeModes.map { mode ->
                    ThemeOption(
                        label = stringResource(themeLabelRes(mode)),
                        selected = selectedMode == mode,
                        onClick = { onSelect(mode) },
                    ) { thumbnailModifier ->
                        AppearanceThumbnail(
                            light = mode != AppCompatDelegate.MODE_NIGHT_YES,
                            dark = mode != AppCompatDelegate.MODE_NIGHT_NO,
                            modifier = thumbnailModifier,
                        )
                    }
                },
            )
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
            ThemeOptionSection(
                title = stringResource(R.string.chat_theme),
                note = stringResource(R.string.chat_theme_note),
                thumbnailHeight = ChatThemeThumbnailHeight,
                options = ChatTheme.entries.map { theme ->
                    ThemeOption(
                        label = stringResource(if (theme == ChatTheme.GRADIENT) R.string.chat_theme_gradient else R.string.chat_theme_classic),
                        selected = theme == chatTheme,
                        onClick = { onSelectChatTheme(theme) },
                    ) { thumbnailModifier -> ChatThemeThumbnail(theme, thumbnailModifier) }
                },
            )
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
            TextSection(chatTheme, largeText, onLargeTextChange)
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
            ScreenLayoutSection(
                displaySize = displaySize,
                onDisplaySizeChange = onDisplaySizeChange,
                navigationPlacement = navigationPlacement,
                onNavigationPlacementChange = onNavigationPlacementChange,
                statusBarHidden = statusBarHidden,
                onStatusBarHiddenChange = onStatusBarHiddenChange,
            )
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ThemeSettingsContentPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 900.dp)) {
            ThemeSettingsContent(
                selectedMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                onSelect = {},
                chatTheme = ChatTheme.CLASSIC,
                onSelectChatTheme = {},
                largeText = false,
                onLargeTextChange = {},
                displaySize = DisplaySize.STANDARD,
                onDisplaySizeChange = {},
                navigationPlacement = NavigationPlacement.RIGHT,
                onNavigationPlacementChange = {},
                statusBarHidden = false,
                onStatusBarHiddenChange = {},
            )
        }
    }
}
