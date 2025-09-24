package com.difft.android.call

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

object LocalImageLoaderProvider {
    // 创建一个 Composable 函数来提供 ImageLoader
    @Composable
    fun localImageLoader(): ImageLoader {
        val context = LocalContext.current
        val imageLoader = remember {
            ImageLoader.Builder(context)
                .components {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(AnimatedImageDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
        }
        return imageLoader
    }
}