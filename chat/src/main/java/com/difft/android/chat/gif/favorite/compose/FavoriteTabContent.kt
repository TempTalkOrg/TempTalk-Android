package com.difft.android.chat.gif.favorite.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.chat.gif.favorite.FavoriteContract
import com.difft.android.chat.gif.favorite.FavoriteGifUiItem
import com.difft.android.chat.gif.favorite.FavoriteViewModel






@Composable
fun FavoriteTabContent(
    viewModel: FavoriteViewModel,
    onPick: (FavoriteGifUiItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier) {
        FavoriteGrid(
            items = state.favorites,
            emptyResult = state.emptyResult,
            onPick = onPick,
            onUnfavorite = { viewModel.dispatch(FavoriteContract.Intent.Unfavorite(it.fileHash)) },
            
            
            resolveGif = viewModel::resolveGif,
            
            
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
