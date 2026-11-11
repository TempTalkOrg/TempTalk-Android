package com.difft.android.base.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.difft.android.base.ui.theme.DifftTheme

@Composable
fun TileStrip(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    items: LazyListScope.() -> Unit,
) {
    val tokens = DifftTheme.contacts
    val state = rememberLazyListState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(tokens.gapLarge)) {
        Text(
            text = "$title · $count",
            modifier = Modifier.padding(horizontal = tokens.cardPadding),
            style = tokens.section,
            color = DifftTheme.colors.textSecondary,
        )
        Box(Modifier.fillMaxWidth().edgeFade(state.canScrollBackward, state.canScrollForward)) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = tokens.gapMedium),
                content = items,
            )
        }
    }
}
