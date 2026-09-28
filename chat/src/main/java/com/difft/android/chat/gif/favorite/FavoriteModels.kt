package com.difft.android.chat.gif.favorite

import android.util.Base64
import com.difft.android.base.utils.globalServices

/**
 * Client-only favorites domain models (live inside the encrypted blob, never sent
 * as plaintext to the server). See android-impl-design.md §B1 and
 * cross-platform-alignment.md §2.6.
 *
 * The on-the-wire JSON shape is [FavoriteListPlainJson] (Base64-string ByteArrays) so the
 * serialized blob is byte-exact across Android / iOS / Mac. [FavoriteRecord] /
 * [FavoriteAttachmentPointer] are the in-memory shapes the rest of the app uses.
 */

/** Attachment pointer for a favorited gif (reuses the existing attachment-pointer format). */
data class FavoriteAttachmentPointer(
    val id: String,
    val authorizeId: Long,
    /** SHA-512(plaintext): required to download + decrypt the asset. */
    val key: ByteArray,
    /** cipherHash. */
    val digest: ByteArray,
    /** Record primary key (content is identity, no separate favoriteId). */
    val fileHash: String,
    val contentType: String = "image/webp",
    val width: Int,
    val height: Int,
    /** Plaintext asset size in bytes (peers validate the download against it). 0 = unknown/legacy. */
    val size: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as FavoriteAttachmentPointer
        return fileHash == other.fileHash
    }

    override fun hashCode(): Int = fileHash.hashCode()
}

/** One confirmed favorite record. [addedListVersion] = the server listVersion at add time. */
data class FavoriteRecord(
    val attachment: FavoriteAttachmentPointer,
    /** Descending sort key = the listVersion when added (server-arbitrated, no clock). */
    val addedListVersion: Long
)

/** The decrypted favorites list (only confirmed records — optimistic pending items are not in the blob). */
data class FavoriteListPlain(val records: List<FavoriteRecord>)

/**
 * Where a pending (not-yet-confirmed) favorite's asset comes from. Persisted as a single JSON column
 * (FavoriteGifModel.pendingSourceJson) — see [toJson]/[pendingSourceFromJson] — and rebuilt here so
 * render (FavoriteGrid) and retry (resolveAndConfirm / flushPendingFavorites) branch on the type.
 * Confirmed rows have NONE (asset lives in the encrypted cache keyed by attachmentId).
 */
sealed interface PendingSource {
    /** No pending asset source — a confirmed row (attachmentId + encKey resolve the cache). */
    data object None : PendingSource

    /** Giphy panel/search: display + send from the preview URL until the background upload lands. */
    data class Remote(val previewUrl: String) : PendingSource

    /**
     * Message gif attachment reference (favorite-without-download). Carries everything needed to
     * (a) derive the account fileHash for the isExist fast-pass, (b) render from local/cached bytes
     * if present, and (c) download+decrypt the message ciphertext on an isExist miss.
     *
     *  - key/digest/authorizeId: the MESSAGE attachment pointer (message-scoped), used ONLY to
     *    download the source bytes on an isExist miss; the CONFIRMED favorite gets a NEW
     *    account-level authorizeId from isExist/uploadInfo (re-authorized under [myId] so the
     *    favorite outlives the message).
     *  - attachmentId: the source attachment's message-domain LOCAL id. Purely internal bookkeeping
     *    — it is persisted with the pending row and never leaves the device.
     *  - messageId + fileName: locate the on-disk `.encrypt` ciphertext for local render / decrypt.
     *    `messageId` is the attachment's DIRECTORY key from `AttachmentPathResolver.directoryKeyFor`
     *    (the copy's own local id), not the id of a message.
     *  - accountFileHash: Base64(SHA-256(key)) precomputed — the isExist fast-pass key.
     */
    data class Message(
        val messageId: String,
        val fileName: String,
        val attachmentId: String,
        val authorizeId: Long,
        val key: ByteArray,
        val digest: ByteArray,
        val accountFileHash: String
    ) : PendingSource {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            other as Message
            return accountFileHash == other.accountFileHash
        }

        override fun hashCode(): Int = accountFileHash.hashCode()
    }
}

/**
 * Persisted form of a [PendingSource] — serialized to the single `FavoriteGifModel.pendingSourceJson`
 * column (mirrors how avatars store a structured blob as one JSON string column, e.g.
 * ContactorModel/GroupModel.avatar). ByteArrays are Base64 NO_WRAP so the JSON stays compact/text-safe.
 * A [type] discriminator disambiguates Remote vs Message; unknown types decode to null (forward-safe).
 */
private data class PendingSourceDto(
    val type: Int,
    val previewUrl: String? = null,       // Remote
    val messageId: String? = null,        // Message ↓
    val fileName: String? = null,
    val attachmentId: String? = null,
    val authorizeId: Long = 0L,
    val encKeyB64: String? = null,
    val digestB64: String? = null,
    val accountFileHash: String? = null
) {
    companion object {
        const val TYPE_REMOTE = 1
        const val TYPE_MESSAGE = 2
    }
}

/** Serialize a pending source for the `pendingSourceJson` column. [PendingSource.None] -> null. */
fun PendingSource.toJson(): String? = when (this) {
    PendingSource.None -> null
    is PendingSource.Remote -> globalServices.gson.toJson(
        PendingSourceDto(type = PendingSourceDto.TYPE_REMOTE, previewUrl = previewUrl)
    )
    is PendingSource.Message -> globalServices.gson.toJson(
        PendingSourceDto(
            type = PendingSourceDto.TYPE_MESSAGE,
            messageId = messageId,
            fileName = fileName,
            attachmentId = attachmentId,
            authorizeId = authorizeId,
            encKeyB64 = Base64.encodeToString(key, Base64.NO_WRAP),
            digestB64 = Base64.encodeToString(digest, Base64.NO_WRAP),
            accountFileHash = accountFileHash
        )
    )
}

/** Rebuild a pending source from the `pendingSourceJson` column. null/blank/unparseable -> null. */
fun pendingSourceFromJson(json: String?): PendingSource? {
    if (json.isNullOrBlank()) return null
    val dto = runCatching { globalServices.gson.fromJson(json, PendingSourceDto::class.java) }.getOrNull() ?: return null
    return when (dto.type) {
        PendingSourceDto.TYPE_REMOTE -> dto.previewUrl?.let { PendingSource.Remote(it) }
        PendingSourceDto.TYPE_MESSAGE -> PendingSource.Message(
            messageId = dto.messageId.orEmpty(),
            fileName = dto.fileName.orEmpty(),
            attachmentId = dto.attachmentId.orEmpty(),
            authorizeId = dto.authorizeId,
            key = dto.encKeyB64?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
            digest = dto.digestB64?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
            accountFileHash = dto.accountFileHash.orEmpty()
        )
        else -> null
    }
}
