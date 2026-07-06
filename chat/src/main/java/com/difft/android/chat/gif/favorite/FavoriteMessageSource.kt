package com.difft.android.chat.gif.favorite

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.util.FileDecryptionUtil
import java.io.File

/** A decrypted send-staging temp older than this is definitely abandoned (in-use ones live <1s). */
private const val GIF_FAV_SRC_STALE_MS = 5 * 60 * 1000L

/**
 * Resolve a readable plaintext file for favoriting a message gif attachment at [basePath]: the
 * plaintext file when present, else a temp decrypted from the encrypted-at-rest ciphertext with
 * [key]. Returns (file, isTemp) — isTemp rides through as [FavoriteSource.FromMessageFile.deleteAfterUse]
 * so the write path deletes the temp — or null when nothing is readable / decrypt fails. Shared by the
 * chat-list and forward-detail long-press menus. Call on Dispatchers.IO.
 */
fun resolveMessageGifPlaintext(context: Context, basePath: String, key: ByteArray?): Pair<File, Boolean>? {
    if (EncryptedAttachmentAccess.hasPlaintext(basePath)) return File(basePath) to false
    val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
    if (!encFile.exists()) return null
    return try {
        val dir = File(context.cacheDir, "gif_fav_src").apply { mkdirs() }
        // Prune orphaned decrypted temps left by aborted favorites (dispatch cancelled, or cap dialog
        // dismissed → deleteAfterUse never honored) so plaintext gifs don't linger in cacheDir. Only
        // stale ones are removed, so a temp actively being consumed by a concurrent favorite is safe.
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > GIF_FAV_SRC_STALE_MS) runCatching { it.delete() } }
        val temp = File(dir, "${now}_${File(basePath).name}")
        FileDecryptionUtil.decryptFile(encFile, temp, key) // verifyMacFirst=true (default)
        temp to true
    } catch (e: Exception) {
        L.w { "[FavoriteMessageSource] decrypt failed: ${e.message}" }
        null
    }
}
