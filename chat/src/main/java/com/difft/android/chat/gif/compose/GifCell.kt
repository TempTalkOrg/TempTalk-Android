package com.difft.android.chat.gif.compose

import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.gif.GifUiItem

/**
 * Grid cell rendering an animated webp via Glide5 inside an AppCompatImageView.
 *
 * Deliberate choices (see android-impl-design.md §A4 / §9 MINOR-1):
 *  - No Glide transform: a BitmapTransformation would freeze WebpDrawable to a static frame
 *    (#5176/#5477).
 *  - Square cells, NO rounded corners (Issue 3): the trending/search grid shows sharp-cornered
 *    cells, so there is no Compose clip here.
 *  - `Glide.with(iv).clear(iv)` before load, so a fast-scroll-recycled ImageView never
 *    shows the previous item's residual frame.
 *
 * [onLongClick] (Issue 4) lets the trending/search grids open the add-to-favorite menu on
 * long-press; it reports the touch position in WINDOW coordinates so the caller can map it into its
 * own layout and pop the menu at the finger (see [GifFavoriteMenu]). The favorites grid does not
 * pass it (it has its own long-press menu), so it defaults to a no-op and the cell stays reusable.
 * Gestures are handled in Compose (not on the ImageView) so the long-press position is available.
 */
@Composable
fun GifCell(
    item: GifUiItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (Offset) -> Unit = {}
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    AndroidView(
        factory = { ctx ->
            AppCompatImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        update = { iv ->
            Glide.with(iv).clear(iv)
            // Timeout comes from the global default in MyAppGlideModule (Glide's own 2.5s default is
            // too short for remote GIPHY previews).
            Glide.with(iv).load(item.webpUrl).into(iv)
        },
        modifier = modifier
            .aspectRatio(item.aspectRatio)
            // Light-gray placeholder shown until the webp loads (avoids blank cells while paging).
            .background(DifftTheme.colors.backgroundTertiary)
            .onGloballyPositioned { coordinates = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    // Report the press in window coordinates: the finger offset is cell-local, and the
                    // menu lives in the grid root, so the caller maps window -> root-local.
                    onLongPress = { offset ->
                        onLongClick(coordinates?.localToWindow(offset) ?: offset)
                    }
                )
            }
    )
}
