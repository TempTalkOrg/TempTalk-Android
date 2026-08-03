package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.sanitizeUrl
import com.difft.android.chat.fileshare.DownloadReq
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.media.EncryptedAttachmentAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the favorites `download → fetchToFile → structural-validate` primitive, shared by
 * [FavoriteGifLoader] (confirmed-cell render) and the message-favorite isExist-miss download path in
 * [FavoriteOptimisticWriter]. Both fetch an attachment's `[IV16][AES-CBC][HMAC32]` ciphertext by its
 * fileshare pointer (authorizeId + fileHash) and keep it AS-IS at rest (no plaintext to disk).
 *
 * Extracted so the ~40-LOC fetch (fileShareRepo.download → pre-signed OSS URLs → structural check →
 * atomic rename) is not duplicated. See design §2.7.
 */
@Singleton
class FavoriteCiphertextFetcher @Inject constructor(
    private val fileShareRepo: FileShareRepo,
    private val userManager: UserManager,
) {
    /**
     * Download the encrypted attachment for ([authorizeId], [fileHash]) and store its structurally-
     * complete ciphertext at [destEncFile] (kept AS-IS, no decryption to disk). Downloads to a sibling
     * `.part` temp, structurally validates it, then atomically renames onto [destEncFile]. Returns
     * false (and cleans up) on any meta/OSS/validation failure. Runs on IO.
     */
    suspend fun downloadCiphertextTo(
        authorizeId: Long,
        fileHash: String,
        destEncFile: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(destEncFile.parentFile, "${destEncFile.name}.part")
        try {
            val token = userManager.getUserData()?.microToken ?: ""
            val resp = fileShareRepo.download(DownloadReq(token, authorizeId, fileHash, "")).execute()
            val body = resp.body()
            if (!resp.isSuccessful || body?.status != 0) {
                L.w { "[FavoriteCiphertextFetcher] download meta failed fileHash=$fileHash http=${resp.code()} status=${body?.status}" }
                return@withContext false
            }
            val urls = body.data?.urls?.takeIf { it.isNotEmpty() } ?: listOfNotNull(body.data?.url)
            if (urls.isEmpty()) {
                L.w { "[FavoriteCiphertextFetcher] no download urls fileHash=$fileHash" }
                return@withContext false
            }
            if (!fetchToFile(urls, tempFile, fileHash)) return@withContext false
            if (!EncryptedAttachmentAccess.isStructurallyCompleteCiphertext(tempFile)) {
                L.w { "[FavoriteCiphertextFetcher] downloaded ciphertext malformed fileHash=$fileHash len=${tempFile.length()}" }
                return@withContext false
            }
            destEncFile.delete()
            if (!tempFile.renameTo(destEncFile)) {
                tempFile.copyTo(destEncFile, overwrite = true)
            }
            true
        } catch (e: Exception) {
            L.w { "[FavoriteCiphertextFetcher] download failed fileHash=$fileHash: ${e.stackTraceToString()}" }
            destEncFile.delete()
            false
        } finally {
            tempFile.delete()
        }
    }

    /** Try each pre-signed URL until one downloads the encrypted bytes into [tempFile]. */
    private fun fetchToFile(urls: List<String>, tempFile: File, fileHash: String): Boolean {
        for (url in urls) {
            try {
                val ossResp = fileShareRepo.downloadFromOSS(url).execute()
                val ossBody = ossResp.body
                if (!ossResp.isSuccessful || ossBody == null) {
                    L.w { "[FavoriteCiphertextFetcher] OSS download failed fileHash=$fileHash url=${url.sanitizeUrl()}" }
                    continue
                }
                ossBody.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                return true
            } catch (e: Exception) {
                L.w { "[FavoriteCiphertextFetcher] OSS download exception fileHash=$fileHash url=${url.sanitizeUrl()}: ${e.message}" }
                if (tempFile.exists()) tempFile.delete()
            }
        }
        return false
    }
}
