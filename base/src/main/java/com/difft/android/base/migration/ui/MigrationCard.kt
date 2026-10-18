package com.difft.android.base.migration.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme

/**
 * A grouped card. Cells inside carry their own inset and are separated by a divider; a card holding
 * prose rather than cells sets [padded] instead. Lives in `:base` so the scanner in `:chat` draws the
 * same card as the migration pages.
 */
@Composable
fun MigrationCard(
    modifier: Modifier = Modifier,
    padded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val card = modifier
        .fillMaxWidth()
        .clip(DifftTheme.radius.largeShape)
        .background(DifftTheme.colors.background)
    Column(
        modifier = if (padded) card.padding(16.dp) else card,
        content = content,
    )
}

/** A padded card holding one paragraph of guidance in the secondary text colour. */
@Composable
fun MigrationNoteCard(text: String, modifier: Modifier = Modifier) {
    MigrationCard(modifier = modifier, padded = true) {
        Text(text = text, style = DifftTheme.typography.p3, color = DifftTheme.colors.textSecondary)
    }
}
