package com.difft.android.chat.gif.favorite

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.util.FileDecryptionUtil
import difft.android.messageserialization.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * Resolve a decrypting `content://` [Uri] for a PENDING message-gif cell from LOCAL bytes only — NO
 * network. Prefers the message's structurally-complete `.encrypt` ciphertext (decrypted on demand by
 * the message provider, keyed by messageId), else the legacy plaintext file, else null → the caller
 * shows the gray placeholder (grid) or a failure toast (send). The confirmed cell's on-demand
 * account-cache download is a separate path (resolveGif); a pending cell must never touch the network.
 * Shared by the grid render (FavoriteGrid) and the pending-send path (ChatMessageInputFragment) so the
 * local-bytes lookup can't drift. Runs on IO.
 */
suspend fun resolveMessageUri(src: PendingSource.Message): Uri? =
    withContext(Dispatchers.IO) {
        val basePath = FileUtil.getMessageAttachmentFilePath(src.messageId) + src.fileName
        EncryptedAttachmentAccess.exportContentUriIfEncrypted(src.messageId, basePath)
            ?: if (EncryptedAttachmentAccess.hasPlaintext(basePath)) File(basePath).toUri() else null
    }

/**
 * Build a [FavoriteSource.FromMessageRef] from a message gif [attachment] (favorite-without-download).
 * Instant (no IO, no decrypt): the account fileHash is derived later in the write path from [Attachment.key].
 * Returns null when the attachment lacks the key or fileName needed to derive the fileHash / locate the
 * source bytes — the caller then shows the failure toast. Shared by the chat-list and forward-detail
 * long-press menus so the ref-building logic can't drift.
 */
fun buildMessageRef(attachment: Attachment, messageId: String): FavoriteSource.FromMessageRef? {
    val key = attachment.key
    val fileName = attachment.fileName
    if (key == null || key.isEmpty() || fileName == null) return null
    return FavoriteSource.FromMessageRef(
        messageId = messageId,
        fileName = fileName,
        attachmentId = attachment.id,
        authorizeId = attachment.authorityId,
        key = key,
        digest = attachment.digest ?: ByteArray(0),
        width = attachment.width,
        height = attachment.height,
        size = attachment.size,
        contentType = attachment.contentType
    )
}
