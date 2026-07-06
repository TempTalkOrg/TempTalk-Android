package com.difft.android.chat.gif.favorite

import androidx.compose.runtime.Immutable
import java.io.File

/**
 * MVI contract for the favorites tab (the "B half" of the GIF feature). v2: the favKey is always
 * recoverable from the account identity (unwrap-first), so there is no key-pending / syncing /
 * manual-reset UI state.
 */
object FavoriteContract {

    sealed interface Intent {
        /** Entering the favorites tab -> pull + ensureFavKey + decrypt. */
        data object OpenFavorites : Intent
        /** Favorite a source (panel gif resolved to a local file, or a message attachment). */
        data class Favorite(val source: FavoriteSource) : Intent
        data class Unfavorite(val fileHash: String) : Intent
        /** User chose "evict oldest and favorite" from the cap dialog. */
        data class EvictOldestThenFavorite(val onEvict: suspend () -> Unit) : Intent
    }

    @Immutable
    data class State(
        /** Decrypted favorites, descending by addedListVersion, pending on top. */
        val favorites: List<FavoriteGifUiItem> = emptyList(),
        val emptyResult: Boolean = false
    )

    sealed interface Effect {
        /** Cap reached on an interactive favorite: evict oldest and favorite, or cancel. */
        data class ShowCapDialog(val onEvictOldest: suspend () -> Unit) : Effect
        /** Toast by string resource id (VM has no Context; the host resolves the string). */
        data class ShowToast(@androidx.annotation.StringRes val messageRes: Int) : Effect
    }
}

/** Source of a favorite action. */
sealed interface FavoriteSource {
    /** From the GIF panel: a resolved local gif file + its dimensions. */
    data class FromFile(val file: File, val width: Int, val height: Int) : FavoriteSource
    /**
     * From a chat message's gif attachment. The host resolves a readable plaintext [file]: the
     * plaintext file when present, else a temp decrypted from the encrypted-at-rest `.encrypt`
     * ciphertext. [deleteAfterUse] is true for that temp so the write path deletes it once consumed.
     */
    data class FromMessageFile(
        val file: File,
        val width: Int,
        val height: Int,
        val deleteAfterUse: Boolean = false
    ) : FavoriteSource

    /**
     * Panel/search optimistic favorite: identified by the GIPHY id + preview URL; no local file yet
     * (download + upload happen in the background). The favorite is instant — a placeholder row is
     * inserted and shown immediately; the real transStore + CAS PUT run app-scoped so they survive the
     * panel closing.
     */
    data class FromRemote(
        val giphyId: String,
        val previewUrl: String,
        val width: Int,
        val height: Int
    ) : FavoriteSource
}
