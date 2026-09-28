package com.difft.android.migration.ui.component

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme

/**
 * The progress page's bar and reading. A [progress] in `[0, 1]` draws a determinate bar; anything
 * else draws the indeterminate one. [metric] is the line under the bar.
 */
@Composable
fun MigrationProgressBlock(
    progress: Float?,
    metric: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val barModifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
        if (progress != null && progress in 0f..1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = barModifier,
                color = DifftTheme.colors.primary,
                trackColor = DifftTheme.colors.backgroundQuaternary,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(
                modifier = barModifier,
                color = DifftTheme.colors.primary,
                trackColor = DifftTheme.colors.backgroundQuaternary,
            )
        }
        if (metric != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = metric,
                style = DifftTheme.typography.p3,
                color = DifftTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Formats `62% · 7.72 GB / 12.40 GB`; with no total known, the percentage alone. */
fun formatTransferMetric(context: Context, progress: Float, totalBytes: Long): String {
    val pct = (progress.coerceIn(0f, 1f) * PERCENT).toInt()
    if (totalBytes <= 0L) return "$pct%"
    val done = (totalBytes * progress.coerceIn(0f, 1f)).toLong()
    return "$pct% · ${formatMigrationBytes(context, done)} / ${formatMigrationBytes(context, totalBytes)}"
}

/**
 * Every byte total the migration screens show, in one place. The long system format keeps two
 * decimals below 100 GB (`1.27 GB`), which is what iOS shows for the same total; the short one rounds
 * to one decimal.
 */
fun formatMigrationBytes(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

private const val PERCENT = 100
