package com.difft.android.migration.ui.component

import com.difft.android.base.migration.ui.MigrationCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.compose.DifftSwitch
import com.difft.android.base.ui.theme.DifftTheme

/** The hairline between two cells of a card. */
@Composable
fun MigrationCardDivider() {
    HorizontalDivider(color = DifftTheme.colors.divider)
}

/** One cell of a grouped card. [tall] is the 72dp variant that carries an icon tile or avatar. */
@Composable
fun MigrationRow(
    modifier: Modifier = Modifier,
    tall: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth()
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .heightIn(min = if (tall) TallRowHeight else RowHeight)
            .padding(horizontal = 16.dp, vertical = if (tall) 16.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Neutral leading tile of a cell: an outline icon on the tertiary surface. */
@Composable
fun MigrationIconTile(iconRes: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(DifftTheme.colors.backgroundTertiary, DifftTheme.radius.largeShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = DifftTheme.colors.icon,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** A choice that is a whole cell: icon tile, name, chevron. Tapping it commits, with no confirm step. */
@Composable
fun MigrationOptionRow(title: String, iconRes: Int, onClick: () -> Unit) {
    MigrationRow(tall = true, onClick = onClick) {
        MigrationIconTile(iconRes)
        Text(
            text = title,
            style = DifftTheme.typography.p2,
            color = DifftTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.migration_ic_chevron_right),
            contentDescription = null,
            tint = DifftTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Leading content (an avatar, an icon tile) next to a name and a quieter line under it. */
@Composable
fun MigrationIdentityRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    MigrationRow(modifier = modifier, tall = true) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(text = title, style = DifftTheme.typography.p2, color = DifftTheme.colors.textPrimary)
            if (subtitle != null) {
                Text(text = subtitle, style = DifftTheme.typography.p3, color = DifftTheme.colors.textSecondary)
            }
        }
    }
}

/** A label with its figure on the right. */
@Composable
fun MigrationValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    MigrationRow(modifier = modifier) {
        Text(
            text = label,
            style = DifftTheme.typography.p2,
            color = DifftTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = DifftTheme.typography.p3, color = DifftTheme.colors.textPrimary)
    }
}

/** A switch cell; [subtitle] states a consequence rather than repeating the label. */
@Composable
fun MigrationSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    MigrationRow(modifier = modifier, tall = subtitle != null) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = DifftTheme.typography.p2, color = DifftTheme.colors.textPrimary)
            if (subtitle != null) {
                Text(text = subtitle, style = DifftTheme.typography.p3, color = DifftTheme.colors.textSecondary)
            }
        }
        DifftSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Small print under a card, aligned with the cells' inset. */
@Composable
fun MigrationCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = DifftTheme.typography.p3,
        color = DifftTheme.colors.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

/** Body copy directly on the page ground. */
@Composable
fun MigrationBodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = DifftTheme.typography.p3,
        color = DifftTheme.colors.textSecondary,
        modifier = modifier.fillMaxWidth(),
    )
}

private val RowHeight = 56.dp
private val TallRowHeight = 72.dp
