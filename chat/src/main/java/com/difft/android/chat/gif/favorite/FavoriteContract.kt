package com.difft.android.chat.gif.favorite

import androidx.compose.runtime.Immutable
import java.io.File






object FavoriteContract {

    sealed interface Intent {
        
        data object OpenFavorites : Intent
        
        data class Favorite(val source: FavoriteSource) : Intent
        data class Unfavorite(val fileHash: String) : Intent
        
        data class EvictOldestThenFavorite(val onEvict: suspend () -> Unit) : Intent
    }

    @Immutable
    data class State(
        
        val favorites: List<FavoriteGifUiItem> = emptyList(),
        val emptyResult: Boolean = false
    )

    sealed interface Effect {
        
        data class ShowCapDialog(val onEvictOldest: suspend () -> Unit) : Effect
        
        data class ShowToast(@androidx.annotation.StringRes val messageRes: Int) : Effect
    }
}


sealed interface FavoriteSource {
    
    data class FromFile(val file: File, val width: Int, val height: Int) : FavoriteSource

    




    data class FromMessageRef(
        




        val messageId: String,
        val fileName: String,
        
        val attachmentId: String,
        val authorizeId: Long,
        val key: ByteArray,
        val digest: ByteArray,
        val width: Int,
        val height: Int,
        val size: Int,
        val contentType: String
    ) : FavoriteSource

    





    data class FromRemote(
        val giphyId: String,
        val previewUrl: String,
        val width: Int,
        val height: Int
    ) : FavoriteSource
}
