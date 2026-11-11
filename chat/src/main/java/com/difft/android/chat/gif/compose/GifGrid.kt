package com.difft.android.chat.gif.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.gif.GifUiItem




@Composable
fun GifGrid(
    items: List<GifUiItem>,
    emptyResult: Boolean,
    onPick: (GifUiItem) -> Unit,
    onLoadNextPage: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    isLoading: Boolean = false,
    loadError: Boolean = false,
    onAddToFavorite: (GifUiItem) -> Unit = {}
) {
    val gridState = rememberLazyStaggeredGridState()
    
    
    
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            total > 0 && last >= total - 4
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadNextPage()
    }

    
    
    
    var menuItemId by remember { mutableStateOf<String?>(null) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoordinates = it },
        contentAlignment = Alignment.Center
    ) {
        when {
            
            isLoading && items.isEmpty() ->
                androidx.compose.material3.CircularProgressIndicator(
                    color = DifftTheme.colors.textTertiary,
                    modifier = Modifier.size(28.dp)
                )

            
            
            loadError && items.isEmpty() ->
                Text(
                    text = stringResource(R.string.gif_load_failed),
                    style = DifftTheme.typography.bodyMedium,
                    color = DifftTheme.colors.textTertiary
                )

            emptyResult ->
                Text(
                    text = stringResource(R.string.gif_no_result),
                    style = DifftTheme.typography.bodyMedium,
                    color = DifftTheme.colors.textTertiary
                )

            else ->
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = gridState,
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = items, key = { it.id }) { item ->
                        GifCell(
                            item = item,
                            onClick = { onPick(item) },
                            onLongClick = { windowPos ->
                                menuItemId = item.id
                                menuPosition = rootCoordinates?.windowToLocal(windowPos) ?: windowPos
                            }
                        )
                    }
                }
        }

        
        
        GifFavoriteMenu(
            visible = menuItemId != null,
            position = menuPosition,
            rootSize = rootCoordinates?.size ?: IntSize.Zero,
            onDismiss = { menuItemId = null },
            iconRes = R.drawable.chat_ic_gif_tab_favorites,
            labelRes = R.string.gif_favorites_add_full,
            onClick = { items.firstOrNull { it.id == menuItemId }?.let(onAddToFavorite) }
        )
    }
}
