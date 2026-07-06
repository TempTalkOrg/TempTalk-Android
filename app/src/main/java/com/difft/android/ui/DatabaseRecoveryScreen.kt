package com.difft.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.DifftThemePreview

/**
 * Full-screen UI shown by [com.difft.android.MainActivity] when DB corruption is
 * detected. Indeterminate spinner (the underlying `retrieve(null)` surfaces no
 * progress), app logo, and recovery status text. Uses [DifftTheme] tokens so it
 * renders correctly in light + dark.
 */
@Composable
fun DatabaseRecoveryScreen(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DifftTheme.colors.background)
            .padding(DifftTheme.spacing.insetXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackXLarge))

        CircularProgressIndicator(
            color = DifftTheme.colors.primary,
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackLarge))

        Text(
            text = stringResource(R.string.db_recovery_in_progress),
            style = DifftTheme.typography.titleMedium,
            color = DifftTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackSmall))

        Text(
            text = stringResource(R.string.db_recovery_please_wait),
            style = DifftTheme.typography.bodyMedium,
            color = DifftTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(
    name = "Default",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
private fun DatabaseRecoveryScreenPreview() {
    DifftThemePreview {
        DatabaseRecoveryScreen()
    }
}

@Preview(
    name = "Accessibility",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    locale = "zh",
    fontScale = 2.0f
)
@Composable
private fun DatabaseRecoveryScreenPreviewAccessibility() {
    DifftThemePreview {
        DatabaseRecoveryScreen()
    }
}
