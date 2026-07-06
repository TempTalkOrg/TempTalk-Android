package com.difft.android.chat.gif

import android.net.Uri
import com.difft.android.network.responses.GifData
import androidx.compose.runtime.Immutable

/**
 * MVI contract for the GIF browse/search panel (the "A half" of the GIF feature).
 *
 * Wires SEARCH + TRENDING + send (M1) and the FAVORITES tab (M3, content driven by
 * FavoriteViewModel). The two MOOD tabs are greyed out until the server category params exist.
 * The tab enum carries all five so the UI and State stay forward-compatible.
 */
object GifPanelContract {

    enum class GifTab { SEARCH, FAVORITES, TRENDING, MOOD_HAPPY, MOOD_SAD }

    sealed interface Intent {
        data class SelectTab(val tab: GifTab) : Intent
        data class Search(val query: String) : Intent
        data object LoadNextPage : Intent
        data object Refresh : Intent
        data class PickGif(val item: GifUiItem) : Intent
        /** Long-press a trending/search cell -> resolve to a local file for favoriting (Issue 5). */
        data class FavoriteGif(val item: GifUiItem) : Intent
    }

    data class State(
        val currentTab: GifTab = GifTab.TRENDING,
        val items: List<GifUiItem> = emptyList(),
        val isLoading: Boolean = false,
        val hasMore: Boolean = false,
        val query: String = "",
        // mood tabs run a fixed search ("happy" / "sad") inline -> enabled
        val moodTabsEnabled: Boolean = true,
        // favorites data flow is wired in M3 -> tab enabled
        val favoritesEnabled: Boolean = true,
        val emptyResult: Boolean = false
    )

    sealed interface Effect {
        data class SendGif(val uri: Uri, val width: Int, val height: Int) : Effect
        data class ShowError(val message: String) : Effect
        /**
         * A long-pressed trending/search gif to favorite optimistically (no pre-download). The host
         * forwards it to FavoriteViewModel (FavoriteSource.FromRemote); the placeholder is inserted +
         * toast fires instantly and the download + trans-store + CAS PUT run in the background.
         */
        data class FavoriteRemote(
            val giphyId: String,
            val previewUrl: String,
            val width: Int,
            val height: Int
        ) : Effect
    }
}

/**
 * UI projection of a [GifData] for the grid. Display AND send/favorite both use [webpUrl] (the
 * preview rendition) — the grid already loads/caches it, so send/favorite need no extra download.
 * [aspectRatio] = width / height, clamped to a sane range so a malformed item can't break the
 * staggered grid layout.
 */
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
        /** Map a network [GifData] to a UI item, or null if it lacks a usable preview URL. */
        fun fromGifData(gif: GifData): GifUiItem? {
            val id = gif.id ?: return null
            val preview = gif.preview ?: return null
            // Prefer webp; fall back to the preview gif URL so an item without a webp is still usable.
            val webp = preview.webp ?: preview.gif ?: return null
            return GifUiItem(
                id = id,
                webpUrl = webp,
                width = preview.width,
                height = preview.height
            )
        }
    }
}
