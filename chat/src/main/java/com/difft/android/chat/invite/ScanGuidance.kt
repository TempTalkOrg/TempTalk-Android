package com.difft.android.chat.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.migration.ui.MigrationBanner
import com.difft.android.base.migration.ui.MigrationNoteCard






@Composable
fun ScanGuidance(note: String?, notice: String?, modifier: Modifier = Modifier) {
    if (note.isNullOrEmpty() && notice.isNullOrEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = DifftTheme.spacing.insetLarge,
                end = DifftTheme.spacing.insetLarge,
                bottom = DifftTheme.spacing.insetXLarge,
            ),
        verticalArrangement = Arrangement.spacedBy(DifftTheme.spacing.insetMedium),
    ) {
        if (!note.isNullOrEmpty()) MigrationNoteCard(note)
        if (!notice.isNullOrEmpty()) MigrationBanner(notice)
    }
}





internal fun scanNote(hint: String?, secondary: String?): String? {
    val lines = listOfNotNull(hint, secondary).filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    return lines.reduce { acc, next -> if (acc.last() in FULL_WIDTH_STOPS) acc + next else "$acc $next" }
}

private const val FULL_WIDTH_STOPS = "。！？；：，」』）"
