package com.difft.android.chat.gif.favorite

import android.net.Uri
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.media.FavoriteEncryptedAttachmentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBFavoriteGifModel
import org.difft.app.database.models.FavoriteGifModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a favorite gif to a decrypting `content://` [Uri], mirroring the message-attachment media
 * flow but stored ENCRYPTED AT REST: the fileshare download is already `[IV16][AES-CBC][HMAC32]`
 * ciphertext, so it is kept as-is at [FilePathManager.gifFavoritesDir]/`<attachmentId>.encrypt` and
 * decrypted on demand by [FavoriteEncryptedAttachmentProvider] — no plaintext ever hits disk. A
 * cache hit (ciphertext present) skips the network.
 *
 * This is the SECOND favorites cache (the first is the WCDB list cache in [FavoriteSyncRepository]).
 * It exists because a favorite row rebuilt from the server blob carries no local file, so the gif is
 * re-fetched by its attachment pointer to render. Works for all sources (just-favorited, server-
 * pulled, cross-device).
 */
@Singleton
class FavoriteGifLoader @Inject constructor(
    private val ciphertextFetcher: FavoriteCiphertextFetcher,
) {
    // One Mutex per in-flight fileHash so concurrent cells don't double-download the same gif.
    private val inFlightLock = Mutex()
    private val inFlight = HashMap<String, Mutex>()

    /**
     * Return a decrypting `content://` [Uri] for [fileHash]'s gif, using the on-disk ciphertext cache
     * when present and otherwise downloading it. The bytes are decrypted on demand by
     * [FavoriteEncryptedAttachmentProvider] — no plaintext is ever written to disk (encrypted-at-rest,
     * matching message attachments). Returns null when the favorite is unknown, the download fails, or
     * required pointer fields are missing.
     */
    suspend fun resolve(fileHash: String): Uri? = withContext(Dispatchers.IO) {
        lockFor(fileHash).withLock {
            val row = wcdb.favoriteGifs.getFirstObject(DBFavoriteGifModel.fileHash.eq(fileHash))
            if (row == null) {
                L.w { "[FavoriteGifLoader] no favorite row fileHash=$fileHash" }
                return@withLock null
            }
            val attachmentId = row.attachmentId
            if (attachmentId.isNullOrEmpty()) {
                L.w { "[FavoriteGifLoader] missing attachmentId fileHash=$fileHash" }
                return@withLock null
            }
            // The decrypting provider needs the row's 64-byte key at open time; if it is missing/short
            // (e.g. a server-rebuilt row that lost the key), bail out HERE so the caller shows a
            // placeholder / no-op instead of getting a uri that later fails in openFile (silent no-send).
            if (!hasUsableKey(row)) {
                L.w { "[FavoriteGifLoader] missing/short encKey fileHash=$fileHash" }
                return@withLock null
            }

            val encFile = FavoriteEncryptedAttachmentProvider.encryptedFile(attachmentId)
            if (EncryptedAttachmentAccess.isStructurallyCompleteCiphertext(encFile)) {
                return@withLock FavoriteEncryptedAttachmentProvider.contentUri(attachmentId) // cache hit
            }
            // Download the ciphertext AS-IS (already `[IV16][AES-CBC][HMAC32]` under the 64-byte
            // attachment key) via the shared fetcher; no decryption to disk.
            if (!ciphertextFetcher.downloadCiphertextTo(row.authorizeId, fileHash, encFile)) return@withLock null
            FavoriteEncryptedAttachmentProvider.contentUri(attachmentId)
        }
    }

    /** The row carries a usable (≥64-byte) attachment key the decrypting provider can open with. */
    private fun hasUsableKey(row: FavoriteGifModel): Boolean {
        val enc = row.encKey
        if (enc.isNullOrEmpty()) return false
        return runCatching { Base64.decode(enc, Base64.NO_WRAP).size }.getOrDefault(0) >= 64
    }

    // Per-fileHash Mutex so concurrent cells coalesce onto one download. The map is bounded by the
    // favorites cap (≤200 distinct fileHashes), so entries are kept rather than ref-counted —
    // avoids a remove-while-waiting race for a negligible, bounded footprint.
    private suspend fun lockFor(fileHash: String): Mutex = inFlightLock.withLock {
        inFlight.getOrPut(fileHash) { Mutex() }
    }
}
