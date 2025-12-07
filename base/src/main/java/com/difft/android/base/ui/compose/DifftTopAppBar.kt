package com.difft.android.base.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Simplified DifftTopAppBar with common patterns built-in.
 * This version provides a string-based API with automatic back button and action text support.
 *
 * Integrates with Material Design 3 and DifftTheme:
 * - Uses `DifftTheme.typography.topBarTitle` for title styling
 * - Uses `DifftTheme.typography.labelLarge` for action text
 * - Uses `DifftTheme.spacing.insetLarge` for padding
 * - Automatically applies semantic colors from theme
 *
 * Example:
 * ```
 * DifftTopAppBar(
 *     title = "Settings",
 *     onNavigateBack = { navController.popBackStack() },
 *     actionText = "Save",
 *     onActionClick = { saveSettings() },
 *     actionEnabled = isFormValid
 * )
 * ```
 *
 * @param title The title text to display
 * @param onNavigateBack Optional callback for back navigation. If provided, a back button is shown
 * @param actionText Optional text for the right action button
 * @param onActionClick Callback for when the action text is clicked
 * @param actionEnabled Whether the action button is enabled
 * @param titleStyle Custom text style for the title. Defaults to DifftTheme.typography.topBarTitle
 * @param modifier Optional modifier for the TopAppBar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DifftTopAppBar(
    title: String,
    onNavigateBack: (() -> Unit)? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    titleStyle: TextStyle? = null,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = titleStyle ?: DifftTheme.typography.topBarTitle,
                color = DifftTheme.colors.textPrimary
            )
        },
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        painter = painterResource(R.drawable.chative_ic_back),
                        contentDescription = "Back",
                        tint = DifftTheme.colors.textPrimary
                    )
                }
            }
        },
        actions = {
            if (actionText != null && onActionClick != null) {
                Text(
                    text = actionText,
                    style = DifftTheme.typography.labelLarge,
                    color = if (actionEnabled) {
                        DifftTheme.colors.primary
                    } else {
                        DifftTheme.colors.textDisabled
                    },
                    modifier = Modifier
                        .clickable(enabled = actionEnabled) { onActionClick() }
                        .padding(horizontal = DifftTheme.spacing.insetLarge)
                )
            }
        },
        colors = difftTopAppBarColors(),
        modifier = modifier
    )
}

/**
 * Flexible DifftTopAppBar for complex cases.
 * This version allows full customization of title, navigation icon, and actions.
 *
 * @param title The title content for the TopAppBar
 * @param modifier Optional modifier for the TopAppBar
 * @param navigationIcon Optional navigation icon composable
 * @param actions Optional action buttons composable
 * @param windowInsets Window insets for the TopAppBar
 * @param colors Colors for the TopAppBar, defaults to difftTopAppBarColors()
 * @param scrollBehavior Optional scroll behavior
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DifftTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = difftTopAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = windowInsets,
        colors = colors,
        scrollBehavior = scrollBehavior
    )
}

/**
 * Creates TopAppBarColors that match the standard AppBar appearance across the app.
 * Colors are consistent with XML layouts using @color/bg1 and @color/t.primary.
 *
 * This function provides sensible defaults that work with both light and dark themes:
 * - Uses `background` for container (matches @color/bg1: #FFFFFF light / #181A20 dark)
 * - Uses `textPrimary` for content colors (matches @color/t.primary: #1E2329 light / #EAECEF dark)
 * - Ensures visual consistency with existing XML-based screens
 *
 * Example:
 * ```
 * TopAppBar(
 *     title = { Text("Title") },
 *     colors = difftTopAppBarColors()
 * )
 * ```
 *
 * @param containerColor Background color for the TopAppBar. Defaults to background (bg1)
 * @param scrolledContainerColor Background color when content is scrolled. Defaults to background (bg1)
 * @param navigationIconContentColor Color for the navigation icon. Defaults to textPrimary (t.primary)
 * @param titleContentColor Color for the title text. Defaults to textPrimary (t.primary)
 * @param actionIconContentColor Color for action icons. Defaults to textPrimary (t.primary)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun difftTopAppBarColors(
    containerColor: Color = DifftTheme.colors.background,
    scrolledContainerColor: Color = DifftTheme.colors.background,
    navigationIconContentColor: Color = DifftTheme.colors.textPrimary,
    titleContentColor: Color = DifftTheme.colors.textPrimary,
    actionIconContentColor: Color = DifftTheme.colors.textPrimary
): TopAppBarColors {
    return TopAppBarDefaults.topAppBarColors(
        containerColor = containerColor,
        scrolledContainerColor = scrolledContainerColor,
        navigationIconContentColor = navigationIconContentColor,
        titleContentColor = titleContentColor,
        actionIconContentColor = actionIconContentColor
    )
}

/**
 * Creates TopAppBarColors for secondary surface elevation.
 * Uses `backgroundSecondary` for a slightly elevated appearance.
 *
 * Use this variant for:
 * - Settings screens with elevated headers
 * - Secondary navigation surfaces
 * - Screens requiring visual separation from main content
 *
 * Example:
 * ```
 * TopAppBar(
 *     title = { Text("Settings") },
 *     colors = difftElevatedTopAppBarColors()
 * )
 * ```
 *
 * @param containerColor Background color for the TopAppBar. Defaults to backgroundSecondary
 * @param scrolledContainerColor Background color when scrolled. Defaults to backgroundSecondary
 * @param navigationIconContentColor Color for navigation icon. Defaults to textPrimary (t.primary)
 * @param titleContentColor Color for title text. Defaults to textPrimary (t.primary)
 * @param actionIconContentColor Color for action icons. Defaults to textPrimary (t.primary)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun difftElevatedTopAppBarColors(
    containerColor: Color = DifftTheme.colors.backgroundSecondary,
    scrolledContainerColor: Color = DifftTheme.colors.backgroundSecondary,
    navigationIconContentColor: Color = DifftTheme.colors.textPrimary,
    titleContentColor: Color = DifftTheme.colors.textPrimary,
    actionIconContentColor: Color = DifftTheme.colors.textPrimary
): TopAppBarColors {
    return TopAppBarDefaults.topAppBarColors(
        containerColor = containerColor,
        scrolledContainerColor = scrolledContainerColor,
        navigationIconContentColor = navigationIconContentColor,
        titleContentColor = titleContentColor,
        actionIconContentColor = actionIconContentColor
    )
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
private fun DifftTopAppBarPreview_Basic() {
    DifftTheme {
        Scaffold(
            topBar = {
                DifftTopAppBar(
                    title = "Settings"
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Content Area", style = DifftTheme.typography.bodyLarge)
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
private fun DifftTopAppBarPreview_WithBack() {
    DifftTheme {
        Scaffold(
            topBar = {
                DifftTopAppBar(
                    title = "Profile Settings",
                    onNavigateBack = {}
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Content Area", style = DifftTheme.typography.bodyLarge)
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
private fun DifftTopAppBarPreview_WithAction() {
    DifftTheme {
        Scaffold(
            topBar = {
                DifftTopAppBar(
                    title = "Edit Profile",
                    onNavigateBack = {},
                    actionText = "Save",
                    onActionClick = {},
                    actionEnabled = true
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Content Area", style = DifftTheme.typography.bodyLarge)
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
private fun DifftTopAppBarPreview_ActionDisabled() {
    DifftTheme {
        Scaffold(
            topBar = {
                DifftTopAppBar(
                    title = "Edit Profile",
                    onNavigateBack = {},
                    actionText = "Save",
                    onActionClick = {},
                    actionEnabled = false
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Content Area", style = DifftTheme.typography.bodyLarge)
            }
        }
    }
}
