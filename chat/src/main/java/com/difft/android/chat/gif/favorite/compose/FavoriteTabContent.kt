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

/**
 * Favorites tab content hosted inside the inline GIF panel: the favorites waterfall grid.
 * v2 has no key-pending / syncing / manual-reset banner (the favKey is always recoverable from
 * the account identity), so this is just the grid.
 */
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
            // Resolve the decrypted gif file lazily per cell (cache hit → no network). Passed in so
            // the grid does no Hilt injection (MVI-clean).
            resolveGif = viewModel::resolveGif,
            // Host ll_chat_actions already insets 16dp horizontally and the panel adds the
            // top inset, so drop those here (no double-padding); keep a 16dp bottom inset.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
