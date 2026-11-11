package com.difft.android.chat.gif

import android.net.Uri
import com.difft.android.network.responses.GifData
import androidx.compose.runtime.Immutable








object GifPanelContract {

    enum class GifTab { SEARCH, FAVORITES, TRENDING, MOOD_HAPPY, MOOD_SAD }

    sealed interface Intent {
        data class SelectTab(val tab: GifTab) : Intent
        data class Search(val query: String) : Intent
        data object LoadNextPage : Intent
        data object Refresh : Intent
        data class PickGif(val item: GifUiItem) : Intent
        
        data class FavoriteGif(val item: GifUiItem) : Intent
    }

    data class State(
        val currentTab: GifTab = GifTab.TRENDING,
        val items: List<GifUiItem> = emptyList(),
        val isLoading: Boolean = false,
        val hasMore: Boolean = false,
        val query: String = "",
        
        val moodTabsEnabled: Boolean = true,
        
        val favoritesEnabled: Boolean = true,
        val emptyResult: Boolean = false,
        
        val loadError: Boolean = false
    )

    sealed interface Effect {
        data class SendGif(val uri: Uri, val width: Int, val height: Int) : Effect
        data class ShowError(val message: String) : Effect
        




        data class FavoriteRemote(
            val giphyId: String,
            val previewUrl: String,
            val width: Int,
            val height: Int
        ) : Effect
    }
}








@Immutable
data class GifUiItem(
    val id: String,
    val webpUrl: String,
    val width: Int,
    val height: Int
) {
    val aspectRatio: Float
        get() {
            if (width <= 0 || height <= 0) return 1f
            return (width.toFloat() / height.toFloat()).coerceIn(0.5f, 2f)
        }

    companion object {
        
        fun fromGifData(gif: GifData): GifUiItem? {
            val id = gif.id ?: return null
            val original = gif.original ?: return null
            
            
            val webp = original.webp ?: original.gif ?: return null
            return GifUiItem(
                id = id,
                webpUrl = webp,
                width = original.width,
                height = original.height
            )
        }
    }
}
