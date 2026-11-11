package com.difft.android.network.responses








data class FavoritesResponse(
    
    val encVersion: Int = 1,
    
    val listVersion: Long = 0L,
    
    val keyId: String? = null,
    
    val blob: String? = null,
    



    val wrappedFavKey: String? = null
)







data class FavoritesPutRequest(
    val encVersion: Int,
    
    val action: String,
    
    val listVersion: Long? = null,
    val keyId: String? = null,
    
    val blob: String? = null,
    
    val items: List<FavoriteItemMeta>? = null,
    
    val wrappedFavKey: String? = null
)





data class FavoriteItemMeta(
    val attachmentId: String,
    
    
    val authorizeId: String,
    val fileHash: String
)


object FavoriteAction {
    const val FAVORITE = "favorite"
    const val UNFAVORITE = "unfavorite"
    
    
    const val REWRAP = "rewrap"
    
    
    const val RESET = "reset"
}
