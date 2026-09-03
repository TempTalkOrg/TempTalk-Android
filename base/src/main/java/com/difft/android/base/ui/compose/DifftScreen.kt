package com.difft.android.base.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme

/**
 * DifftScreen combines Scaffold with the standard [TitleBar] for a consistent screen layout.
 * All page-level title bars in the app share TitleBar's spec (52dp, left-aligned bold title).
 *
 * Insets: neither DifftScreen nor TitleBar consumes the status bar inset, and BaseActivity
 * skips auto system-bar padding for Compose roots — the caller owns it. Apply
 * `Modifier.systemBarsPadding()` on the root around DifftScreen (project convention),
 * otherwise the title bar renders under the status bar.
 *
 * Common patterns:
 * - Basic screen with title: `Box(Modifier.systemBarsPadding()) { DifftScreen(title = "Settings") { ... } }`
 * - Screen with back button: `DifftScreen(title = "Settings", onNavigateBack = {}) { ... }`
 * - Custom top bar (extra actions, search, etc.): use the [topBar] overload with [TitleBar]
 *   or a custom composable.
 *
 * @param title The title text to display in the top bar
 * @param onNavigateBack Optional callback for back navigation. If provided, a back button is shown
 * @param containerColor Background color for the screen. Defaults to theme background
 * @param modifier Optional modifier for the Scaffold
 * @param content The main content of the screen, receives PaddingValues to respect system bars
 */
@Composable
fun DifftScreen(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    containerColor: Color = DifftTheme.colors.background,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TitleBar(
                titleText = title,
                showBackButton = onNavigateBack != null,
                onBackClick = { onNavigateBack?.invoke() }
            )
        },
        containerColor = containerColor,
        modifier = modifier
    ) { paddingValues ->
        content(paddingValues)
    }
}

/**
 * DifftScreen with custom top bar for complex cases.
 * Use this variant when you need full control over the top bar content beyond what
 * the standard TitleBar provides (e.g., custom layouts, multiple actions, search bars).
 *
 * Example:
 * ```
 * DifftScreen(
 *     topBar = {
 *         TitleBar(titleText = "Devices", titleEndText = "(3)", onBackClick = onBack)
 *     }
 * ) { padding ->
 *     // Content
 * }
 * ```
 *
 * @param topBar Custom top bar composable (typically [TitleBar])
 * @param containerColor Background color for the screen. Defaults to theme background
 * @param modifier Optional modifier for the Scaffold
 * @param content The main content of the screen, receives PaddingValues to respect system bars
 */
@Composable
fun DifftScreen(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = DifftTheme.colors.background,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = topBar,
        containerColor = containerColor,
        modifier = modifier
    ) { paddingValues ->
        content(paddingValues)
    }
}

// ============== Preview Composables ==============

@Preview(name = "Light Theme - Basic", showBackground = true)
@Preview(
    name = "Dark Theme - Basic",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun DifftScreenPreview_Basic() {
    DifftTheme {
        DifftScreen(
            title = "Settings"
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(DifftTheme.spacing.insetLarge),
                verticalArrangement = Arrangement.spacedBy(DifftTheme.spacing.stackMedium)
            ) {
                Text(
                    "Screen Title: Settings",
                    style = DifftTheme.typography.titleMedium,
                    color = DifftTheme.colors.textPrimary
                )
                Text(
                    "This is a basic screen with just a title.",
                    style = DifftTheme.typography.bodyMedium,
                    color = DifftTheme.colors.textSecondary
                )
            }
        }
    }
}

@Preview(name = "Light Theme - With Back", showBackground = true)
@Preview(
    name = "Dark Theme - With Back",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun DifftScreenPreview_WithBack() {
    DifftTheme {
        DifftScreen(
            title = "Profile Settings",
            onNavigateBack = {}
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(DifftTheme.spacing.insetLarge),
                verticalArrangement = Arrangement.spacedBy(DifftTheme.spacing.stackMedium)
            ) {
                Text(
                    "Screen with Back Button",
                    style = DifftTheme.typography.titleMedium,
                    color = DifftTheme.colors.textPrimary
                )
                Text(
                    "Navigation back is enabled.",
                    style = DifftTheme.typography.bodyMedium,
                    color = DifftTheme.colors.textSecondary
                )
            }
        }
    }
}
