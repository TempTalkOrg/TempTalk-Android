package com.difft.android.base.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme

/**
 * DifftScreen combines Scaffold and DifftTopAppBar for a consistent screen layout.
 * This component reduces boilerplate and ensures all screens follow Material Design 3 patterns.
 *
 * Integrates with DifftTheme and Material Design 3:
 * - Automatically applies theme colors (background, surface, text)
 * - Uses semantic spacing from DifftTheme.spacing
 * - Uses typography from DifftTheme.typography
 * - Respects MD3 color roles for accessibility
 *
 * Common patterns:
 * - Basic screen with title: `DifftScreen(title = "Settings") { ... }`
 * - Screen with back button: `DifftScreen(title = "Settings", onNavigateBack = {}) { ... }`
 * - Screen with action: `DifftScreen(title = "Edit", actionText = "Save", onActionClick = {}) { ... }`
 *
 * Example:
 * ```
 * @Composable
 * fun SettingsScreen(onNavigateBack: () -> Unit) {
 *     DifftScreen(
 *         title = "Settings",
 *         onNavigateBack = onNavigateBack
 *     ) { padding ->
 *         Column(
 *             modifier = Modifier
 *                 .fillMaxSize()
 *                 .padding(padding)
 *                 .padding(DifftTheme.spacing.insetLarge)
 *         ) {
 *             Text("Settings content", style = DifftTheme.typography.bodyLarge)
 *         }
 *     }
 * }
 * ```
 *
 * @param title The title text to display in the top bar
 * @param onNavigateBack Optional callback for back navigation. If provided, a back button is shown
 * @param actionText Optional text for the right action button
 * @param onActionClick Callback for when the action text is clicked
 * @param actionEnabled Whether the action button is enabled
 * @param containerColor Background color for the screen. Defaults to theme background
 * @param titleStyle Custom text style for the title. Defaults to DifftTheme.typography.topBarTitle
 * @param modifier Optional modifier for the Scaffold
 * @param content The main content of the screen, receives PaddingValues to respect system bars
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DifftScreen(
    title: String,
    onNavigateBack: (() -> Unit)? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    containerColor: Color = DifftTheme.colors.background,
    titleStyle: TextStyle? = null,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            DifftTopAppBar(
                title = title,
                onNavigateBack = onNavigateBack,
                actionText = actionText,
                onActionClick = onActionClick,
                actionEnabled = actionEnabled,
                titleStyle = titleStyle
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
 * the standard DifftTopAppBar provides (e.g., custom layouts, multiple actions, search bars).
 *
 * Example:
 * ```
 * DifftScreen(
 *     topBar = {
 *         TopAppBar(
 *             title = { Text("Custom Title") },
 *             actions = {
 *                 IconButton(onClick = {}) { Icon(...) }
 *                 IconButton(onClick = {}) { Icon(...) }
 *             }
 *         )
 *     }
 * ) { padding ->
 *     // Content
 * }
 * ```
 *
 * @param topBar Custom top bar composable (typically a Material3 TopAppBar variant)
 * @param containerColor Background color for the screen. Defaults to theme background
 * @param modifier Optional modifier for the Scaffold
 * @param content The main content of the screen, receives PaddingValues to respect system bars
 */
@Composable
fun DifftScreen(
    topBar: @Composable () -> Unit,
    containerColor: Color = DifftTheme.colors.background,
    modifier: Modifier = Modifier,
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

@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Light Theme - With Action", showBackground = true)
@Preview(
    name = "Dark Theme - With Action",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun DifftScreenPreview_WithAction() {
    DifftTheme {
        DifftScreen(
            title = "Edit Profile",
            onNavigateBack = {},
            actionText = "Save",
            onActionClick = {},
            actionEnabled = true
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(DifftTheme.spacing.insetLarge),
                verticalArrangement = Arrangement.spacedBy(DifftTheme.spacing.stackMedium)
            ) {
                Text(
                    "Screen with Action Button",
                    style = DifftTheme.typography.titleMedium,
                    color = DifftTheme.colors.textPrimary
                )
                Text(
                    "The Save action is enabled and clickable.",
                    style = DifftTheme.typography.bodyMedium,
                    color = DifftTheme.colors.textSecondary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Light Theme - Action Disabled", showBackground = true)
@Preview(
    name = "Dark Theme - Action Disabled",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun DifftScreenPreview_ActionDisabled() {
    DifftTheme {
        DifftScreen(
            title = "Edit Profile",
            onNavigateBack = {},
            actionText = "Save",
            onActionClick = {},
            actionEnabled = false
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(DifftTheme.spacing.insetLarge),
                verticalArrangement = Arrangement.spacedBy(DifftTheme.spacing.stackMedium)
            ) {
                Text(
                    "Screen with Disabled Action",
                    style = DifftTheme.typography.titleMedium,
                    color = DifftTheme.colors.textPrimary
                )
                Text(
                    "The Save action is disabled (grayed out).",
                    style = DifftTheme.typography.bodyMedium,
                    color = DifftTheme.colors.textSecondary
                )
            }
        }
    }
}
