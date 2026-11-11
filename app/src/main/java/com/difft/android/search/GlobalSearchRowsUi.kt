package com.difft.android.search

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.search.mvi.GlobalSearchContract.Scope
import com.difft.android.search.mvi.GlobalSearchContract.Section
import com.difft.android.base.R as BaseR
import com.difft.android.chat.R as ChatR




@Composable
private fun Scope.label(): String = when (this) {
    Scope.ALL -> stringResource(ChatR.string.contact_all)
    Scope.CONTACTS -> stringResource(ChatR.string.search_title_contacts)
    Scope.GROUPS -> stringResource(ChatR.string.search_title_group)
    Scope.MESSAGES -> stringResource(R.string.search_section_messages)
}


@Composable
private fun Section.title(): String = scope.label()





@Composable
fun SearchScopePills(selected: Scope, onSelect: (Scope) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Scope.entries.forEach { scope ->
            val isSelected = scope == selected
            Box(
                modifier = Modifier
                    .background(if (isSelected) DifftTheme.colors.primary else Color.Transparent, DifftTheme.radius.pill)
                    .then(if (isSelected) Modifier else Modifier.border(1.dp, DifftTheme.colors.line, DifftTheme.radius.pill))
                    
                    .clip(DifftTheme.radius.pill)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(scope) })
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = scope.label(),
                    style = if (isSelected) DifftTheme.typography.h6 else DifftTheme.typography.p3,
                    color = if (isSelected) DifftTheme.colors.onPrimary else DifftTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}


@Composable
fun SearchSectionHeader(section: Section) {
    Text(
        text = section.title(),
        style = DifftTheme.typography.p4,
        color = DifftTheme.colors.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .background(DifftTheme.colors.background)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}





@Composable
fun SearchDestinationRow(count: Int?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 41.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = if (count != null) pluralStringResource(R.plurals.search_view_all_count, count, count) else stringResource(R.string.search_view_more),
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.primary,
        )
    }
}





@Composable
fun SearchEmptyState(query: String, minHeightPx: Int) {
    val minHeight = with(LocalDensity.current) { minHeightPx.toDp() }.coerceAtLeast(160.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(DifftTheme.colors.backgroundTertiary, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(BaseR.drawable.base_ic_search),
                contentDescription = null,
                tint = DifftTheme.colors.textTertiary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (query.isEmpty()) stringResource(R.string.search_chats_hint) else stringResource(R.string.search_no_results_for, query),
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
