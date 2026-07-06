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

/**
 * Favorites waterfall grid (descending addedListVersion, pending on top). Tap sends the gif,
 * long-press opens a one-row floating menu ("Remove from Favorite") that, when tapped, calls
 * [onUnfavorite] (Issue 4 — was a direct unfavorite). Empty state matches a new device. Pending
 * items render slightly dimmed.
 *
 * No pull-to-refresh: the favorites list re-pulls silently on tab open (OpenFavorites); a manual
 * pull gesture would add a refresh indicator with no real value here.
 */
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
    // Long-press opens the floating menu for this item hash (null = closed). menuPosition = the press
    // point in the grid root's local pixels (converted from the cell's window coordinates), so the
    // menu pops up at the finger.
    var menuItemHash by remember { mutableStateOf<String?>(null) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Dismiss the long-press menu if its target row leaves the list — e.g. an optimistic placeholder
    // gets confirmed and its fileHash changes from "giphy:<id>" to the real hash. Otherwise the menu
    // would keep a stale hash and its Remove tap would silently match nothing. On dismiss the user can
    // just long-press the (now confirmed) cell again.
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
    // Root Box hosts the grid plus the long-press menu overlay (rendered once, un-clipped, same
    // window) so the menu lands at the finger. See [GifFavoriteMenu].
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

/**
 * Favorite cell: resolves a decrypting `content://` Uri via [resolveGif] (cache hit → no network,
 * else download the ciphertext) rather than relying on the DB localPath. The gif is stored encrypted
 * at rest and decrypted on demand, so Glide's disk cache is disabled (NONE) to avoid persisting
 * decrypted plaintext frames. Shows the gray placeholder until resolved. Square (no corner clip),
 * matching the trending/search cells.
 */
@Composable
private fun FavoriteCell(
    item: FavoriteGifUiItem,
    resolveGif: suspend (String) -> android.net.Uri?,
    onClick: () -> Unit,
    onLongClick: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    // Optimistic placeholder rows (sourceUrl set, no real attachment yet) display from the preview URL
    // directly — the uploaded ciphertext resolveGif needs isn't available until the background upload
    // completes. Confirmed rows resolve the decrypting content:// uri by fileHash as before.
    val isOptimistic = item.sourceUrl != null && item.attachmentId == null
    var resolvedUri by remember(item.fileHash) { mutableStateOf<android.net.Uri?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(item.fileHash) {
        resolvedUri = if (isOptimistic) null else resolveGif(item.fileHash)
    }

    AndroidView(
        factory = { ctx ->
            AppCompatImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        update = { iv ->
            Glide.with(iv).clear(iv)
            // Load once resolved; until then the gray background placeholder shows. Disk cache is
            // DISABLED (NONE): the encrypted RESOURCE cache is opt-in per request (ENCRYPT_ANIMATED_CACHE)
            // and this animated load does not set it, so RESOURCE would persist decrypted plaintext
            // frames. The source stays encrypted at rest (.encrypt via the provider); the in-memory
            // cache still serves rebinds within the session.
            if (isOptimistic) {
                // Preview URL is a plaintext remote gif (not encrypted at rest) — default disk cache OK.
                Glide.with(iv).load(item.sourceUrl).into(iv)
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
            // Light-gray placeholder shown until the gif resolves (and for pending items).
            .background(DifftTheme.colors.backgroundTertiary)
            .onGloballyPositioned { coordinates = it }
            .pointerInput(item.fileHash) {
                detectTapGestures(
                    onTap = { onClick() },
                    // Report the press in window coordinates so the grid root can map it (see GifCell).
                    onLongPress = { offset ->
                        onLongClick(coordinates?.localToWindow(offset) ?: offset)
                    }
                )
            }
    )
}
