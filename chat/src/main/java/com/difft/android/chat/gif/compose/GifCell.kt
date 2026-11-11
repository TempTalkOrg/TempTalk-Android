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
            
            
            Glide.with(iv).load(item.webpUrl).into(iv)
        },
        modifier = modifier
            .aspectRatio(item.aspectRatio)
            
            .background(DifftTheme.colors.backgroundTertiary)
            .onGloballyPositioned { coordinates = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    
                    
                    onLongPress = { offset ->
                        onLongClick(coordinates?.localToWindow(offset) ?: offset)
                    }
                )
            }
    )
}
