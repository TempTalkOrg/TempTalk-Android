package com.difft.android.chat.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R

/**
 * The retiring package's persistent "move to the new app" entry in the home title bar: a highlighted
 * pill with the transfer mark and a one-word label, sitting where the other title-bar actions do.
 * Visibility is the host's decision; the pill itself only opens the guide page.
 */
@Composable
fun LegacyMigrationEntryChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(DifftTheme.radius.pill)
            .background(DifftTheme.colors.backgroundBlue)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.chat_ic_migrate),
            contentDescription = null,
            tint = DifftTheme.colors.textPrimary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.legacy_migration_entry_short),
            style = DifftTheme.typography.h7,
            color = DifftTheme.colors.textPrimary,
        )
    }
}
