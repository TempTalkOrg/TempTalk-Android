package com.difft.android.base.migration.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme

/** Banner emphasis: an aside in the secondary text colour, or a caution in the caution colour. */
enum class MigrationBannerTone { INFO, WARNING }

/**
 * Inline notice in the same geometry as a grouped card; only the text colour changes with the tone.
 * Errors get a page of their own rather than a banner.
 */
@Composable
fun MigrationBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: MigrationBannerTone = MigrationBannerTone.WARNING,
    iconRes: Int = R.drawable.migration_ic_alert_triangle,
) {
    val color = when (tone) {
        MigrationBannerTone.INFO -> DifftTheme.colors.textSecondary
        MigrationBannerTone.WARNING -> DifftTheme.colors.textCaution
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DifftTheme.colors.background, DifftTheme.radius.largeShape)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (tone == MigrationBannerTone.WARNING) color else DifftTheme.colors.icon,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = DifftTheme.typography.p3, color = color)
    }
}
