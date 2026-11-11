package com.difft.android.chat.gif.favorite.compose

import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.gif.compose.GifFavoriteMenu
import com.difft.android.chat.gif.favorite.FavoriteGifUiItem



@Composable
fun FavoriteGrid(
    items: List<FavoriteGifUiItem>,
    emptyResult: Boolean,
    onPick: (FavoriteGifUiItem) -> Unit,
    onUnfavorite: (FavoriteGifUiItem) -> Unit,
    resolveGif: suspend (String) -> android.net.Uri?,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp)
) {
    
    
    
    var menuItemHash by remember { mutableStateOf<String?>(null) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    
    
    
    
    LaunchedEffect(items, menuItemHash) {
        if (menuItemHash != null && items.none { it.fileHash == menuItemHash }) {
            menuItemHash = null
        }
    }

    if (emptyResult) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.gif_favorites_empty),
                style = DifftTheme.typography.bodyMedium,
                color = DifftTheme.colors.textTertiary
            )
        }
        return
    }
    
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoordinates = it }
    ) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            items(items = items, key = { it.fileHash }) { item ->
                FavoriteCell(
                    item = item,
                    resolveGif = resolveGif,
                    onClick = { onPick(item) },
                    onLongClick = { windowPos ->
                        menuItemHash = item.fileHash
                        menuPosition = rootCoordinates?.windowToLocal(windowPos) ?: windowPos
                    }
                )
            }
        }

        GifFavoriteMenu(
            visible = menuItemHash != null,
            position = menuPosition,
            rootSize = rootCoordinates?.size ?: IntSize.Zero,
            onDismiss = { menuItemHash = null },
            iconRes = R.drawable.chat_ic_gif_favorite_remove,
            labelRes = R.string.gif_favorites_remove,
            onClick = { items.firstOrNull { it.fileHash == menuItemHash }?.let(onUnfavorite) }
        )
    }
}








@Composable
private fun FavoriteCell(
    item: FavoriteGifUiItem,
    resolveGif: suspend (String) -> android.net.Uri?,
    onClick: () -> Unit,
    onLongClick: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    
    
    
    val src = item.pendingSource
    var resolvedUri by remember(item.fileHash) { mutableStateOf<android.net.Uri?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(item.fileHash) {
        resolvedUri = when (src) {
            is com.difft.android.chat.gif.favorite.PendingSource.Remote -> null 
            is com.difft.android.chat.gif.favorite.PendingSource.Message ->
                com.difft.android.chat.gif.favorite.resolveMessageUri(src) 
            com.difft.android.chat.gif.favorite.PendingSource.None -> resolveGif(item.fileHash)
        }
    }

    AndroidView(
        factory = { ctx ->
            AppCompatImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        update = { iv ->
            Glide.with(iv).clear(iv)
            
            
            
            
            if (src is com.difft.android.chat.gif.favorite.PendingSource.Remote) {
                
                Glide.with(iv).load(src.previewUrl).into(iv)
            } else {
                resolvedUri?.let {
                    Glide.with(iv).load(it)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                        .into(iv)
                }
            }
        },
        modifier = modifier
            .aspectRatio(item.aspectRatio)
            .alpha(if (item.pending) 0.5f else 1f)
            
            .background(DifftTheme.colors.backgroundTertiary)
            .onGloballyPositioned { coordinates = it }
            .pointerInput(item.fileHash) {
                detectTapGestures(
                    onTap = { onClick() },
                    
                    onLongPress = { offset ->
                        onLongClick(coordinates?.localToWindow(offset) ?: offset)
                    }
                )
            }
    )
}
