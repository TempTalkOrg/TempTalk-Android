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

/**
 * 2-column staggered grid of GIF cells (cell gap 8dp, aspect-ratio sized) for the trending and
 * search surfaces. Triggers [onLoadNextPage] when the last item becomes visible (上拉加载).
 *
 * No pull-to-refresh: the trending/search grids live inside the input panel and the search
 * bottom-sheet, where a pull gesture would fight the sheet's swipe-to-dismiss (Issue 4b). A
 * centered loading spinner ([isLoading]) is shown instead while the first page is fetching.
 * Long-press a cell opens a one-row floating menu ("Add to Favorite") that, when tapped, calls
 * [onAddToFavorite] (Issue 4). Shows an empty-state label when [emptyResult] is true.
 */
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
    onAddToFavorite: (GifUiItem) -> Unit = {}
) {
    val gridState = rememberLazyStaggeredGridState()
    // Read the item count from layoutInfo (reactive snapshot state), NOT the captured [items]
    // parameter: a `remember { derivedStateOf { ... items ... } }` freezes [items] to its first
    // composition value (empty), so the threshold never updates and load-more never fires.
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

    // Long-press opens the floating menu for this item id (null = closed). Tracked by id (not
    // index) so it survives list updates / paging. menuPosition = the press point in the grid root's
    // local pixels (converted from the cell's window coordinates), so the menu pops up at the finger.
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
            // First-page load (no items yet): centered spinner instead of an empty grid.
            isLoading && items.isEmpty() ->
                androidx.compose.material3.CircularProgressIndicator(
                    color = DifftTheme.colors.textTertiary,
                    modifier = Modifier.size(28.dp)
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

        // Long-press floating menu, rendered once at the grid root (un-clipped, same window as the
        // grid) so it lands at the finger in both the inline panel and the search bottom sheet.
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
