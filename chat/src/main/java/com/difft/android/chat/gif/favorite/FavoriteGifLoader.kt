package com.difft.android.chat.gif.favorite

import android.net.Uri
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.FilePathManager
import com.difft.android.base.utils.sanitizeUrl
import com.difft.android.chat.fileshare.DownloadReq
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.media.FavoriteEncryptedAttachmentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBFavoriteGifModel
import org.difft.app.database.models.FavoriteGifModel
import org.difft.app.database.wcdb
import java.io.File
import java.io.FileOutputStream
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
    private val fileShareRepo: FileShareRepo,
    private val userManager: UserManager,
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
            if (!downloadCiphertext(row, attachmentId, encFile, fileHash)) return@withLock null
            FavoriteEncryptedAttachmentProvider.contentUri(attachmentId)
        }
    }

    /**
     * Download the encrypted attachment and keep it AS-IS (it is already `[IV16][AES-CBC][HMAC32]`
     * ciphertext under the 64-byte attachment key), storing it at [encFile]. No decryption to disk.
     */
    private fun downloadCiphertext(
        row: FavoriteGifModel,
        attachmentId: String,
        encFile: File,
        fileHash: String,
    ): Boolean {
        val tempFile = File(FilePathManager.gifFavoritesDir, "$attachmentId.encrypt.part")
        try {
            val token = userManager.getUserData()?.microToken ?: ""
            val resp = fileShareRepo.download(DownloadReq(token, row.authorizeId, fileHash, "")).execute()
            val body = resp.body()
            if (!resp.isSuccessful || body?.status != 0) {
                L.w { "[FavoriteGifLoader] download meta failed fileHash=$fileHash http=${resp.code()} status=${body?.status}" }
                return false
            }
            val urls = body.data?.urls?.takeIf { it.isNotEmpty() } ?: listOfNotNull(body.data?.url)
            if (urls.isEmpty()) {
                L.w { "[FavoriteGifLoader] no download urls fileHash=$fileHash" }
                return false
            }

            if (!fetchToFile(urls, tempFile, fileHash)) return false
            if (!EncryptedAttachmentAccess.isStructurallyCompleteCiphertext(tempFile)) {
                L.w { "[FavoriteGifLoader] downloaded ciphertext malformed fileHash=$fileHash len=${tempFile.length()}" }
                return false
            }

            encFile.delete()
            if (!tempFile.renameTo(encFile)) {
                tempFile.copyTo(encFile, overwrite = true)
            }
            return true
        } catch (e: Exception) {
            L.w { "[FavoriteGifLoader] resolve failed fileHash=$fileHash: ${e.stackTraceToString()}" }
            encFile.delete()
            return false
        } finally {
            tempFile.delete()
        }
    }

    /** The row carries a usable (≥64-byte) attachment key the decrypting provider can open with. */
    private fun hasUsableKey(row: FavoriteGifModel): Boolean {
        val enc = row.encKey
        if (enc.isNullOrEmpty()) return false
        return runCatching { Base64.decode(enc, Base64.NO_WRAP).size }.getOrDefault(0) >= 64
    }

    /** Try each pre-signed URL until one downloads the encrypted bytes into [tempFile]. */
    private fun fetchToFile(urls: List<String>, tempFile: File, fileHash: String): Boolean {
        for (url in urls) {
            try {
                val ossResp = fileShareRepo.downloadFromOSS(url).execute()
                val ossBody = ossResp.body
                if (!ossResp.isSuccessful || ossBody == null) {
                    L.w { "[FavoriteGifLoader] OSS download failed fileHash=$fileHash url=${url.sanitizeUrl()}" }
                    continue
                }
                ossBody.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                return true
            } catch (e: Exception) {
                L.w { "[FavoriteGifLoader] OSS download exception fileHash=$fileHash url=${url.sanitizeUrl()}: ${e.message}" }
                if (tempFile.exists()) tempFile.delete()
            }
        }
        return false
    }

    // Per-fileHash Mutex so concurrent cells coalesce onto one download. The map is bounded by the
    // favorites cap (≤200 distinct fileHashes), so entries are kept rather than ref-counted —
    // avoids a remove-while-waiting race for a negligible, bounded footprint.
    private suspend fun lockFor(fileHash: String): Mutex = inFlightLock.withLock {
        inFlight.getOrPut(fileHash) { Mutex() }
    }
}
