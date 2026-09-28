package com.difft.android.migration.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.theme.DifftTheme

/**
 * One action as data, so a page can describe its buttons without composing them itself. The primary
 * is a filled button, the secondary a plain text button, red only when it discards something.
 */
data class MigrationAction(
    val text: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** Primary (filled) full-width action. */
@Composable
fun MigrationPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    DifftButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Full-width text button: the second choice under a primary, or the only exit on a status page. */
@Composable
fun MigrationTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val color = when {
        !enabled -> DifftTheme.colors.textDisabled
        danger -> DifftTheme.colors.textError
        else -> DifftTheme.colors.textSecondary
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = DifftTheme.radius.largeShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = color),
    ) {
        Text(text = text, style = DifftTheme.typography.p2)
    }
}

/** The page footer: a primary above an optional text button, both full width. */
@Composable
fun MigrationButtonStack(
    primary: MigrationAction?,
    secondary: MigrationAction?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (primary != null) {
            MigrationPrimaryButton(text = primary.text, onClick = primary.onClick, enabled = primary.enabled)
        }
        if (secondary != null) {
            MigrationTextButton(
                text = secondary.text,
                onClick = secondary.onClick,
                enabled = secondary.enabled,
                danger = secondary.danger,
            )
        }
    }
}

/** Secondary action; renders [MigrationTextButton]. */
@Composable
fun MigrationSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
) {
    MigrationTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier.padding(vertical = DifftTheme.spacing.insetXSmall),
        danger = isDestructive,
    )
}
