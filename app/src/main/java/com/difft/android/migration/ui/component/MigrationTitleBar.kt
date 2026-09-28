package com.difft.android.migration.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme

/** What sits at the start of a migration page's title bar. */
enum class MigrationNavIcon { BACK, CLOSE, NONE }

/**
 * Title bar for the migration pages: back (or close) at the start, title left-aligned next to it.
 * Pages that must not be left pass [MigrationNavIcon.NONE] and keep the same height with no
 * control, so content does not jump between pages.
 */
@Composable
fun MigrationTitleBar(
    title: String?,
    navIcon: MigrationNavIcon,
    onNav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (navIcon) {
            MigrationNavIcon.NONE -> Spacer(Modifier.width(16.dp))
            MigrationNavIcon.BACK, MigrationNavIcon.CLOSE -> IconButton(onClick = onNav) {
                Icon(
                    painter = painterResource(
                        if (navIcon == MigrationNavIcon.BACK) {
                            com.difft.android.base.R.drawable.chative_ic_back
                        } else {
                            R.drawable.migration_ic_close
                        },
                    ),
                    contentDescription = null,
                    tint = DifftTheme.colors.icon,
                )
            }
        }
        if (title != null) {
            Text(
                text = title,
                style = DifftTheme.typography.h4,
                color = DifftTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val TitleBarHeight = 52.dp
