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
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.wcdb
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
        val basePath = pendingMessageBasePath(src.messageId, src.fileName)
        // Segment derived from the RESOLVED path, never from the captured key: after the attachment
        // migration the two differ, and a uri naming the old directory serves nothing.
        if (EncryptedAttachmentAccess.hasEncrypted(basePath)) {
            EncryptedAttachmentAccess.contentUriFromBasePath(basePath)
        } else if (EncryptedAttachmentAccess.hasPlaintext(basePath)) {
            File(basePath).toUri()
        } else {
            null
        }
    }

/**
 * On-disk base path for a pending message ref. The persisted [PendingSource.Message.messageId] is
 * the directory key AT CAPTURE TIME: a ref captured before per-copy addressing carries the owner
 * message id (or, for a single-forward bubble, the attachment's authorityId as text), whose directory
 * the attachment migration may since have renamed onto the row's own localId. When the captured key
 * no longer resolves, [currentDirectoryKeyFor] names the row it addressed and supplies the CURRENT
 * key, so a pre-migration pending favorite still finds its local bytes instead of depending on a
 * (possibly expired) remote copy.
 *
 * Shared by every pending-message read — grid render, pending send, and the optimistic writer's
 * local/plaintext lookups — so none of them can be left resolving a stale address. Runs on IO
 * (DB read).
 */
internal fun pendingMessageBasePath(messageId: String, fileName: String): String {
    val captured = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
    if (EncryptedAttachmentAccess.isReadable(captured)) return captured
    val currentKey = runCatching { currentDirectoryKeyFor(messageId, fileName) }
        .getOrNull() ?: return captured
    return FileUtil.getMessageAttachmentFilePath(currentKey) + fileName
}

/**
 * The row a legacy pending ref's [capturedKey] addressed, resolved to that row's CURRENT directory
 * key. Null when no row can be named.
 *
 * Both legacy key shapes must be tried. A message-owned attachment was keyed by its owner message id,
 * which the row carries in `messageId`. A single-forward bubble was keyed by the attachment's
 * authorityId as text, and a forward-tree row carries NO `messageId` at all (it hangs off a
 * ForwardModel), so the authorityId arm is the only one that can ever name it. Only a numeric key can
 * be an authorityId, so the second lookup cannot misfire on a message id or a localId (both
 * non-numeric).
 */
private fun currentDirectoryKeyFor(capturedKey: String, fileName: String): String? {
    wcdb.attachment.getFirstObject(
        DBAttachmentModel.messageId.eq(capturedKey)
            .and(DBAttachmentModel.fileName.eq(fileName))
    )?.localId?.takeIf { it.isNotEmpty() }?.let { return it }
    val authorityId = capturedKey.toLongOrNull()?.takeIf { it != 0L } ?: return null
    return wcdb.attachment.getFirstObject(
        DBAttachmentModel.authorityId.eq(authorityId)
            .and(DBAttachmentModel.fileName.eq(fileName))
    )?.localId?.takeIf { it.isNotEmpty() }
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
        // The copy's own local id, not the server-side attachment id: this value is internal to the
        // favorites domain (see FavoriteSource.FromMessageRef) and never reaches the wire — the
        // confirmed favorite's outbound id comes from isExist/uploadInfo, under the account.
        attachmentId = attachment.localId,
        authorizeId = attachment.authorityId,
        key = key,
        digest = attachment.digest ?: ByteArray(0),
        width = attachment.width,
        height = attachment.height,
        size = attachment.size,
        contentType = attachment.contentType
    )
}
