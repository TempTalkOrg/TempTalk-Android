package com.difft.android.chat.gif.favorite

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
