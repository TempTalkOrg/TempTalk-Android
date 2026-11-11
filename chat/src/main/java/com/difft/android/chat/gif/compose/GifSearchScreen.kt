package com.difft.android.chat.gif.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.base.ui.compose.FloatingIconButton
import com.difft.android.base.ui.compose.input.DifftInputSurface
import com.difft.android.base.ui.compose.input.DifftSearchBar
import com.difft.android.chat.R
import com.difft.android.chat.gif.GifPanelContract
import com.difft.android.chat.gif.GifPanelViewModel












@Composable
fun GifSearchScreen(
    viewModel: GifPanelViewModel,
    onPick: (com.difft.android.chat.gif.GifUiItem) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            
            
            
            
            .nestedScroll(rememberNestedScrollInteropConnection())
    ) {
        
        
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            
            
            DifftSearchBar(
                surface = DifftInputSurface.Popup,
                query = state.query,
                onQueryChange = { viewModel.dispatch(GifPanelContract.Intent.Search(it)) },
                onClear = { viewModel.dispatch(GifPanelContract.Intent.Search("")) },
                hint = stringResource(R.string.gif_search_hint),
                modifier = Modifier.weight(1f)
            )
            
            
            FloatingIconButton(
                iconRes = R.drawable.chat_icon_close,
                contentDescription = stringResource(R.string.chat_select_dialog_close),
                onClick = onClose,
                
                
                iconSize = 14.dp,
            )
        }

        GifGrid(
            items = state.items,
            
            
            emptyResult = state.emptyResult && state.query.isNotEmpty(),
            onPick = onPick,
            onLoadNextPage = { viewModel.dispatch(GifPanelContract.Intent.LoadNextPage) },
            isLoading = state.isLoading,
            loadError = state.loadError,
            onAddToFavorite = { item -> viewModel.dispatch(GifPanelContract.Intent.FavoriteGif(item)) },
            
            
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        )
    }
}
