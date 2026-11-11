package com.difft.android.chat.gif.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.chat.gif.GifPanelContract
import com.difft.android.chat.gif.GifPanelViewModel
import com.difft.android.chat.gif.favorite.FavoriteGifUiItem
import com.difft.android.chat.gif.favorite.FavoriteViewModel
import com.difft.android.chat.gif.favorite.compose.FavoriteTabContent





@Composable
fun GifInlinePanel(
    viewModel: GifPanelViewModel,
    favoriteViewModel: FavoriteViewModel,
    onOpenSearch: () -> Unit,
    onPickFavorite: (FavoriteGifUiItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val onFavoritesSelected = state.currentTab == GifPanelContract.GifTab.FAVORITES
    LaunchedEffect(onFavoritesSelected) {
        if (onFavoritesSelected) {
            favoriteViewModel.dispatch(
                com.difft.android.chat.gif.favorite.FavoriteContract.Intent.OpenFavorites
            )
        }
    }

    
    
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        GifTabBar(
            selectedTab = state.currentTab,
            favoritesEnabled = state.favoritesEnabled,
            moodTabsEnabled = state.moodTabsEnabled,
            onTabClick = { tab ->
                if (tab == GifPanelContract.GifTab.SEARCH) {
                    onOpenSearch()
                } else {
                    viewModel.dispatch(GifPanelContract.Intent.SelectTab(tab))
                }
            }
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (onFavoritesSelected) {
            FavoriteTabContent(
                viewModel = favoriteViewModel,
                onPick = onPickFavorite,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            GifGrid(
                items = state.items,
                emptyResult = state.emptyResult,
                onPick = { item -> viewModel.dispatch(GifPanelContract.Intent.PickGif(item)) },
                onLoadNextPage = { viewModel.dispatch(GifPanelContract.Intent.LoadNextPage) },
                contentPadding = PaddingValues(bottom = 16.dp),
                isLoading = state.isLoading,
                loadError = state.loadError,
                onAddToFavorite = { item -> viewModel.dispatch(GifPanelContract.Intent.FavoriteGif(item)) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}
