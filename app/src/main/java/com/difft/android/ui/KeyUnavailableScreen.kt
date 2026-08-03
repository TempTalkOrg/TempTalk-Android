package com.difft.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.R as BaseR
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.DifftThemePreview

/** Minimum accessible touch target for the action button (Material accessibility guideline). */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * Full-screen fail-soft UI shown by [com.difft.android.MainActivity] when the WCDB cipher key is
 * unavailable ([org.difft.app.database.DbHealth.KEY_UNAVAILABLE]) for an existing user (DB file
 * present). The encrypted database can't be opened, but the local data is NOT deleted — it stays
 * on disk behind the (temporarily unavailable) Keystore key. This screen is therefore reassuring,
 * NOT destructive: the only action is a plain retry (process restart, since cipher-key resolution
 * is cached for the process lifetime — a new process re-attempts the Keystore read).
 *
 * Stateless / hoisted — mirrors the [DatabaseRecoveryScreen] idiom; DifftTheme tokens render
 * correctly in light + dark. Design source: NONE (no Figma; reuses the recovery-screen layout).
 */
@Composable
fun KeyUnavailableScreen(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DifftTheme.colors.background)
            .padding(DifftTheme.spacing.insetXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ic_launcher_foreground is a pure-white vector — invisible on a light background.
        // Reconstruct the adaptive-icon look (white mark on its dark circular backdrop) so the
        // logo reads in BOTH themes. The backdrop uses @color/ic_launcher_background (from :base,
        // hence BaseR) — the SAME resource the launcher adaptive-icon uses — intentionally
        // theme-independent (the app icon is identical in light + dark), hence colorResource, not
        // a DifftTheme token.
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(colorResource(id = BaseR.color.ic_launcher_background)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(120.dp)
            )
        }

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackXLarge))

        Text(
            text = stringResource(R.string.db_key_unavailable_title),
            style = DifftTheme.typography.titleMedium,
            color = DifftTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackSmall))

        Text(
            text = stringResource(R.string.db_key_unavailable_message),
            style = DifftTheme.typography.bodyMedium,
            color = DifftTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackXLarge))

        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = DifftTheme.colors.primary,
                contentColor = DifftTheme.colors.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH_TARGET)
        ) {
            Text(
                text = stringResource(R.string.db_key_unavailable_retry),
                style = DifftTheme.typography.labelLarge
            )
        }
    }
}

@Preview(name = "Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun KeyUnavailableScreenLightPreview() {
    DifftThemePreview {
        KeyUnavailableScreen(onRetry = {})
    }
}

@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun KeyUnavailableScreenDarkPreview() {
    DifftThemePreview {
        KeyUnavailableScreen(onRetry = {})
    }
}
