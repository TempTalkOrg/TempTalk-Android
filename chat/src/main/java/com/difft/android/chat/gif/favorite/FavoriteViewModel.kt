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
    private val optimisticWriter: FavoriteOptimisticWriter,
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
                // Optimistic: hides instantly (tombstone), syncs the removal in the background. Toast-free
                // — the instant disappearance IS the feedback. Fixes the old silent-fail-offline bug.
                launchSafely("unfavorite") { optimisticWriter.unfavorite(intent.fileHash) }
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
        // cache, then flush any optimistic pending rows so a deferred add never leaks forever. Each step
        // has its OWN guard so a failed key/pull (offline / transient GET) cannot skip the flush — the
        // flush has its own no-key/offline guards and is idempotent, so it's always safe to run. Offline
        // just keeps the cache already shown above (incl. optimistic pending rows), retrying next open.
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
                // Panel/search optimistic favorite: placeholder + toast instantly; the real download +
                // trans-store + CAS PUT run app-scoped in the background (no size guard — GIPHY previews
                // are always small; no blocking). Toast the SUCCESS string only when the optimistic
                // enqueue actually succeeds; a thrown failure (e.g. WCDB upsert) shows the failure toast.
                emitFavoriteResult {
                    optimisticWriter.favoriteRemote(source.giphyId, source.previewUrl, source.width, source.height)
                }
            }
            is FavoriteSource.FromMessageRef -> {
                // Message gif optimistic favorite (favorite-without-download): placeholder + toast
                // instantly, background resolve (isExist fast-pass, download only on a miss). Cap is
                // deferred to the confirm-time FIFO eviction (matches the Giphy optimistic path).
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
                // Same optimistic contract as FromRemote: success toast only on success; a thrown
                // failure (defensive — favoriteMessage is throw-free after the local-bump fix) toasts
                // the failure string instead of skipping feedback.
                emitFavoriteResult {
                    optimisticWriter.favoriteMessage(ref, source.width, source.height, source.size, source.contentType)
                }
            }
            is FavoriteSource.FromFile -> favoriteFromFile(source)
        }
        L.i { "[FavoriteVM] favorite done" }
    }

    /**
     * Run an optimistic favorite [block] and surface the outcome consistently: [gif_favorites_added]
     * on success, [gif_favorites_failed] if the block throws. Keeps the Remote/Message optimistic
     * paths from emitting a success toast after a call that can fail (Bug E). Cancellation propagates.
     */
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

    /**
     * Blocking panel favorite path for a resolved plaintext file (kept for compatibility; the message
     * entries now use the optimistic [FavoriteSource.FromMessageRef] path). Surfaces the interactive
     * cap dialog on CapReached.
     */
    private suspend fun favoriteFromFile(source: FavoriteSource.FromFile) {
        val file = source.file
        // Reject oversized gifs up front (before any upload/store).
        if (file.length() > MAX_FAVORITE_ASSET_BYTES) {
            L.w { "[FavoriteVM] favorite rejected: size=${file.length()} exceeds $MAX_FAVORITE_ASSET_BYTES" }
            _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_add_size_limit))
            return
        }
        try {
            when (val r = writeRepo.favorite(file, source.width, source.height)) {
                is FavResult.CapReached -> _effect.send(
                    // Confirm dialog: "Replace oldest" evicts + re-runs the favorite; "Cancel" just dismisses.
                    FavoriteContract.Effect.ShowCapDialog(onEvictOldest = {
                        r.onEvictOldest()
                        dispatch(FavoriteContract.Intent.Favorite(source))
                    })
                )
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
            // e.g. network failure during trans-store/upload — surface a toast instead of crashing.
            L.w { "[FavoriteVM] favorite failed: ${e.stackTraceToString()}" }
            _effect.send(FavoriteContract.Effect.ShowToast(com.difft.android.chat.R.string.gif_favorites_failed))
        }
    }
}
