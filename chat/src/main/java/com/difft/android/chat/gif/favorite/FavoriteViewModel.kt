package com.difft.android.chat.gif.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject



const val MAX_FAVORITE_ASSET_BYTES = 10 * 1024 * 1024



@HiltViewModel
class FavoriteViewModel @Inject constructor(
    private val syncRepo: FavoriteSyncRepository,
    private val writeRepo: FavoriteWriteRepository,
    private val optimisticWriter: FavoriteOptimisticWriter,
    private val gifLoader: FavoriteGifLoader
) : ViewModel() {

    




    suspend fun resolveGif(fileHash: String): android.net.Uri? = gifLoader.resolve(fileHash)

    private val _state = MutableStateFlow(FavoriteContract.State())
    val state: StateFlow<FavoriteContract.State> = _state.asStateFlow()

    private val _effect = Channel<FavoriteContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        
        viewModelScope.launch {
            syncRepo.observeFavorites().collect { items ->
                _state.update { it.copy(favorites = items, emptyResult = items.isEmpty()) }
            }
        }
    }

    fun dispatch(intent: FavoriteContract.Intent) {
        when (intent) {
            FavoriteContract.Intent.OpenFavorites -> ensureKeyAndReconcile()
            is FavoriteContract.Intent.Favorite -> onFavorite(intent.source)
            is FavoriteContract.Intent.Unfavorite ->
                
                launchSafely("unfavorite") { optimisticWriter.unfavorite(intent.fileHash) }
            is FavoriteContract.Intent.EvictOldestThenFavorite ->
                launchSafely("evictOldest") { intent.onEvict() }
        }
    }

    





    private fun launchSafely(tag: String, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[FavoriteVM] $tag failed: ${e.stackTraceToString()}" }
        }
    }

    private fun ensureKeyAndReconcile() = launchSafely("openFavorites") {
        
        
        
        
        syncRepo.refreshObservable()
        
        
        
        
        
        runCatching { writeRepo.ensureFavKey() }
            .onFailure { L.w { "[FavoriteVM] ensureFavKey failed: ${it.message}" } }
        runCatching { syncRepo.pullAndDecrypt() }
            .onFailure { L.w { "[FavoriteVM] pull failed: ${it.message}" } }
        runCatching { writeRepo.flushPendingFavorites() }
            .onFailure { L.w { "[FavoriteVM] flush failed: ${it.message}" } }
    }

    private fun onFavorite(source: FavoriteSource) = launchSafely("favorite") {
        when (source) {
            is FavoriteSource.FromRemote -> {
                
                
                
                
                emitFavoriteResult {
                    optimisticWriter.favoriteRemote(source.giphyId, source.previewUrl, source.width, source.height)
                }
            }
            is FavoriteSource.FromMessageRef -> {
                
                
                
                if (source.size > MAX_FAVORITE_ASSET_BYTES) {
                    L.w { "[FavoriteVM] favorite message rejected: size=${source.size} exceeds $MAX_FAVORITE_ASSET_BYTES" }
                    _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_add_size_limit))
                    return@launchSafely
                }
                val accountFileHash = android.util.Base64.encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(source.key),
                    android.util.Base64.NO_WRAP
                )
                val ref = PendingSource.Message(
                    messageId = source.messageId, fileName = source.fileName,
                    attachmentId = source.attachmentId, authorizeId = source.authorizeId,
                    key = source.key, digest = source.digest, accountFileHash = accountFileHash
                )
                
                emitFavoriteResult {
                    optimisticWriter.favoriteMessage(ref, source.width, source.height, source.size, source.contentType)
                }
            }
            is FavoriteSource.FromFile -> favoriteFromFile(source)
        }
        L.i { "[FavoriteVM] favorite done" }
    }

    

    private suspend fun emitFavoriteResult(block: suspend () -> Unit) {
        try {
            block()
            _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_added))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[FavoriteVM] optimistic favorite failed: ${e.stackTraceToString()}" }
            _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_failed))
        }
    }

    
    private suspend fun favoriteFromFile(source: FavoriteSource.FromFile) {
        val file = source.file
        
        if (file.length() > MAX_FAVORITE_ASSET_BYTES) {
            L.w { "[FavoriteVM] favorite rejected: size=${file.length()} exceeds $MAX_FAVORITE_ASSET_BYTES" }
            _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_add_size_limit))
            return
        }
        try {
            when (val r = writeRepo.favorite(file, source.width, source.height)) {
                is FavResult.CapReached -> _effect.send(
                    
                    FavoriteContract.Effect.ShowCapDialog(onEvictOldest = {
                        r.onEvictOldest()
                        dispatch(FavoriteContract.Intent.Favorite(source))
                    })
                )
                
                
                
                FavResult.SyncDeferred -> L.i { "[FavoriteVM] favorite kept pending (deferred sync)" }
                FavResult.Ok -> _effect.send(
                    FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_added)
                )
                FavResult.Rejected -> _effect.send(
                    FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_failed)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            
            L.w { "[FavoriteVM] favorite failed: ${e.stackTraceToString()}" }
            _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_failed))
        }
    }
}
