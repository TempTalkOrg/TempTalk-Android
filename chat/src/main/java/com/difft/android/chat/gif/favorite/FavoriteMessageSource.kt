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


private const val GIF_FAV_SRC_STALE_MS = 5 * 60 * 1000L








fun resolveMessageGifPlaintext(context: Context, basePath: String, key: ByteArray?): Pair<File, Boolean>? {
    if (EncryptedAttachmentAccess.hasPlaintext(basePath)) return File(basePath) to false
    val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
    if (!encFile.exists()) return null
    return try {
        val dir = File(context.cacheDir, "gif_fav_src").apply { mkdirs() }
        
        
        
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > GIF_FAV_SRC_STALE_MS) runCatching { it.delete() } }
        val temp = File(dir, "${now}_${File(basePath).name}")
        FileDecryptionUtil.decryptFile(encFile, temp, key) 
        temp to true
    } catch (e: Exception) {
        L.w { "[FavoriteMessageSource] decrypt failed: ${e.message}" }
        null
    }
}










suspend fun resolveMessageUri(src: PendingSource.Message): Uri? =
    withContext(Dispatchers.IO) {
        val basePath = pendingMessageBasePath(src.messageId, src.fileName)
        
        
        if (EncryptedAttachmentAccess.hasEncrypted(basePath)) {
            EncryptedAttachmentAccess.contentUriFromBasePath(basePath)
        } else if (EncryptedAttachmentAccess.hasPlaintext(basePath)) {
            File(basePath).toUri()
        } else {
            null
        }
    }














internal fun pendingMessageBasePath(messageId: String, fileName: String): String {
    val captured = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
    if (EncryptedAttachmentAccess.isReadable(captured)) return captured
    val currentKey = runCatching { currentDirectoryKeyFor(messageId, fileName) }
        .getOrNull() ?: return captured
    return FileUtil.getMessageAttachmentFilePath(currentKey) + fileName
}












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








fun buildMessageRef(attachment: Attachment, messageId: String): FavoriteSource.FromMessageRef? {
    val key = attachment.key
    val fileName = attachment.fileName
    if (key == null || key.isEmpty() || fileName == null) return null
    return FavoriteSource.FromMessageRef(
        messageId = messageId,
        fileName = fileName,
        
        
        
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
