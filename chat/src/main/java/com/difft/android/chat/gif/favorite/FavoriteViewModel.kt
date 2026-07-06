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
import java.io.File
import javax.inject.Inject

/** Max plaintext size of a gif that can be added to favorites (10 MB). Larger ones are rejected.
 *  Message/forward entry points can pre-check the known Attachment.size against this before decrypting. */
const val MAX_FAVORITE_ASSET_BYTES = 10 * 1024 * 1024

/**
 * ViewModel for the favorites tab. Pull happens on OpenFavorites (dispatched by GifInlinePanel when
 * the favorites tab is shown), not on init, so opening a conversation costs no network call.
 *
 * v2: the favKey is always recoverable from the account identity (unwrap-first). Opening the tab
 * ensures the key, decrypts the server blob into the cache, and flushes any optimistic pending
 * rows. There is no key-pending / syncing / manual-reset UI.
 */
@HiltViewModel
class FavoriteViewModel @Inject constructor(
    private val syncRepo: FavoriteSyncRepository,
    private val writeRepo: FavoriteWriteRepository,
    private val gifLoader: FavoriteGifLoader
) : ViewModel() {

    /**
     * Resolve a favorite gif's fileHash to a decrypting `content://` Uri (cache hit → no network, else
     * download the ciphertext). Passed down to the grid cell so the Composable does no Hilt injection
     * (MVI-clean). Returns null when the gif cannot be resolved (unknown / network / missing pointer).
     */
    suspend fun resolveGif(fileHash: String): android.net.Uri? = gifLoader.resolve(fileHash)

    private val _state = MutableStateFlow(FavoriteContract.State())
    val state: StateFlow<FavoriteContract.State> = _state.asStateFlow()

    private val _effect = Channel<FavoriteContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        // Cache -> State.favorites (already pending-on-top, descending by addedListVersion).
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
                launchSafely("unfavorite") { writeRepo.unfavorite(intent.fileHash) }
            is FavoriteContract.Intent.EvictOldestThenFavorite ->
                launchSafely("evictOldest") { intent.onEvict() }
        }
    }

    /**
     * Launch on [viewModelScope], logging (never rethrowing) any non-cancellation failure. Favorites
     * work hits the network (key fetch / pull / CAS PUT); a bare launch would let an offline IOException
     * escape onto Dispatchers.Main and crash the whole app. This safety net makes an API failure degrade
     * (keep the local cache / skip) instead of crashing the favorites UI.
     */
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
        // Offline-first: show the local DB cache immediately, THEN refresh from the network. The
        // observable ([_favorites]) starts empty each process and is only populated by refreshObservable
        // (reads WCDB). The network pull below would otherwise be the first thing to trigger it, so an
        // offline pull (IOException) would leave the panel blank even though favorites are cached locally.
        syncRepo.refreshObservable()
        // Establish the favKey (unwrap-first / first-create), pull + decrypt the server blob into the
        // cache, then flush any optimistic pending rows so a deferred add never leaks forever. Offline /
        // API failure here just keeps the cache already shown above (incl. optimistic pending rows).
        writeRepo.ensureFavKey()
        syncRepo.pullAndDecrypt()
        writeRepo.flushPendingFavorites()
    }

    private fun onFavorite(source: FavoriteSource) = launchSafely("favorite") {
        // Panel/search optimistic favorite: insert the placeholder + toast instantly; the real
        // download + trans-store + CAS PUT run app-scoped in the background (no size guard — GIPHY
        // previews are always small; no blocking).
        if (source is FavoriteSource.FromRemote) {
            writeRepo.favoriteOptimistic(source.giphyId, source.previewUrl, source.width, source.height)
            _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_added))
            return@launchSafely
        }
        val file: File
        val width: Int
        val height: Int
        val deleteAfterUse: Boolean
        when (source) {
            is FavoriteSource.FromFile -> {
                file = source.file; width = source.width; height = source.height; deleteAfterUse = false
            }
            is FavoriteSource.FromMessageFile -> {
                file = source.file; width = source.width; height = source.height; deleteAfterUse = source.deleteAfterUse
            }
            is FavoriteSource.FromRemote -> return@launchSafely // handled above (unreachable)
        }
        // Reject oversized gifs up front (before any upload/store); clean up the transient temp if any.
        if (file.length() > MAX_FAVORITE_ASSET_BYTES) {
            L.w { "[FavoriteVM] favorite rejected: size=${file.length()} exceeds $MAX_FAVORITE_ASSET_BYTES" }
            if (deleteAfterUse) runCatching { file.delete() }
            _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_add_size_limit))
            return@launchSafely
        }
        // A CapReached outcome re-dispatches this same source after the user confirms eviction, so the
        // (possibly temp) file must survive for that retry — only delete it on a terminal outcome.
        var reused = false
        try {
            when (val r = writeRepo.favorite(file, width, height)) {
                is FavResult.CapReached -> {
                    reused = true
                    _effect.send(
                        // Confirm dialog: "Replace oldest" evicts + re-runs the favorite; "Cancel" just dismisses.
                        FavoriteContract.Effect.ShowCapDialog(onEvictOldest = {
                            r.onEvictOldest()
                            dispatch(FavoriteContract.Intent.Favorite(source))
                        })
                    )
                }
                // Kept locally as an optimistic pending row (rare: identity-rotation window / CAS
                // contention prevented server confirmation). Re-syncs silently on the next
                // favorites-tab open (flushPendingFavorites) — no user-facing toast.
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
            // e.g. network failure during trans-store/upload — surface a toast instead of crashing
            // (the upload runs before the optimistic row is added, so there is nothing to roll back).
            L.w { "[FavoriteVM] favorite failed: ${e.stackTraceToString()}" }
            _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_failed))
        } finally {
            // Delete the transient decrypted-from-message temp once consumed (not on CapReached, which
            // reuses it on retry). Runs on terminal success/failure/cancellation.
            if (deleteAfterUse && !reused) runCatching { file.delete() }
        }
        L.i { "[FavoriteVM] favorite done" }
    }
}
