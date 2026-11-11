package com.difft.android.migration.ui.component

import com.difft.android.base.R as BaseR
import com.difft.android.base.migration.ui.MigrationCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme


enum class MigrationResultTone { INFO, SUCCESS, ERROR, WARNING }






@Composable
fun MigrationResultPage(
    tone: MigrationResultTone,
    title: String,
    primary: MigrationAction? = null,
    secondary: MigrationAction? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    MigrationPage(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        scrollable = false,
        footer = if (primary == null && secondary == null) {
            null
        } else {
            { MigrationButtonStack(primary = primary, secondary = secondary) }
        },
    ) {
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MigrationStateMark(tone)
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = DifftTheme.typography.h4,
                color = DifftTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            content()
        }
        Spacer(Modifier.weight(1f))
    }
}


@Composable
fun MigrationStateMark(tone: MigrationResultTone, modifier: Modifier = Modifier) {
    val (iconRes, color) = when (tone) {
        MigrationResultTone.INFO -> R.drawable.migration_ic_transfer to DifftTheme.colors.icon
        MigrationResultTone.SUCCESS -> R.drawable.migration_ic_circle_check to DifftTheme.colors.textSuccess
        MigrationResultTone.ERROR -> R.drawable.migration_ic_alert_circle to DifftTheme.colors.textError
        MigrationResultTone.WARNING -> BaseR.drawable.migration_ic_alert_triangle to DifftTheme.colors.textCaution
    }
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = color,
        modifier = modifier.size(StateMarkSize),
    )
}


@Composable
fun MigrationResultBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = DifftTheme.typography.p3,
        color = DifftTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = ResultBodyMaxWidth),
    )
}


@Composable
fun MigrationReasonCard(label: String, reason: String, modifier: Modifier = Modifier) {
    MigrationCard(modifier = modifier, padded = true) {
        Text(
            text = label,
            style = DifftTheme.typography.p4,
            color = DifftTheme.colors.textTertiary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = reason,
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textPrimary,
        )
    }
}

private val StateMarkSize = 48.dp
private val ResultBodyMaxWidth = 300.dp
