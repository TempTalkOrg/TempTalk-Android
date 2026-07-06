package com.difft.android.network.responses

/**
 * Favorites contract DTOs (GIF favorites, three-platform shared contract — see
 * cross-platform-alignment.md §2 and android-impl-design.md §B1).
 *
 * The server is zero-knowledge: it only stores the ciphertext [FavoritesResponse.blob]
 * plus plaintext action/items and a set of non-key version numbers. The encrypted list
 * content (which gif is which) is never visible to the server.
 *
 * Field names/types/nesting MUST stay identical across Android / iOS / Mac.
 */

/** GET /gifs/v1/gifs/favorites response (also echoed back by PUT with the new listVersion). */
data class FavoritesResponse(
    /** Encryption scheme version (plaintext, outside the ciphertext). This release = 1. */
    val encVersion: Int = 1,
    /** List version = optimistic-lock CAS token (monotonic, server-authoritative). */
    val listVersion: Long = 0L,
    /** favKey fingerprint (NOT the key). null = favorites were never created. */
    val keyId: String? = null,
    /** Base64(AES-256-GCM(favKey, whole list)); null = empty list. */
    val blob: String? = null,
    /**
     * v2: favKey wrapped under a KEK derived from the account identity (self-describing envelope
     * JSON string, see FavoriteCrypto). Opaque to the server. null = favorites were never created.
     */
    val wrappedFavKey: String? = null
)

/**
 * PUT /gifs/v1/gifs/favorites request body. One PUT carries exactly one action; the fields Gson
 * serializes depend on the action, so the action-specific fields are nullable and omitted when
 * unused (favorite/unfavorite carry listVersion+keyId+blob+items; rewrap carries only
 * wrappedFavKey; reset carries blob+items+keyId+wrappedFavKey, no listVersion).
 */
data class FavoritesPutRequest(
    val encVersion: Int,
    /** "favorite" | "unfavorite" | "rewrap" | "reset". */
    val action: String,
    /** CAS expected value; carried only for favorite/unfavorite (rewrap/reset omit it). */
    val listVersion: Long? = null,
    val keyId: String? = null,
    /** New whole-list ciphertext. */
    val blob: String? = null,
    /** Plaintext file info the server uses to pin/unpin assets. */
    val items: List<FavoriteItemMeta>? = null,
    /** v2: favKey wrapped under the account-identity KEK; carried on first-create / rewrap / reset. */
    val wrappedFavKey: String? = null
)

/**
 * Plaintext item meta (server-visible — attachment pointer only, does not leak which gif).
 * favorite needs all fields; unfavorite only needs [attachmentId].
 */
data class FavoriteItemMeta(
    val attachmentId: String,
    // String, NOT Long: the server (and file-sharing isExists/pin) treats authorizeId as a string
    // id; sending it as a JSON number is rejected with status=1 "invalid param".
    val authorizeId: String,
    val fileHash: String
)

/** Favorites PUT action constants (wire values, three-platform aligned). */
object FavoriteAction {
    const val FAVORITE = "favorite"
    const val UNFAVORITE = "unfavorite"
    // v2: update ONLY the wrappedFavKey column (favKey re-wrapped under the new account-identity
    // KEK on identity rotation). No listVersion, no CAS — independent-column last-write-wins.
    const val REWRAP = "rewrap"
    // Replace the whole pinned set: the server unpins ALL of this user's previously-pinned
    // attachments, then pins the ones in this request (empty items => just unpin all / clear).
    // v2: fired automatically on primary-device re-registration (old key unrecoverable) — a new
    // favKey + wrappedFavKey is written alongside. No user-facing entry.
    const val RESET = "reset"
}
