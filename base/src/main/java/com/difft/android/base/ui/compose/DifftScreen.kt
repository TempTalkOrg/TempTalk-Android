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
