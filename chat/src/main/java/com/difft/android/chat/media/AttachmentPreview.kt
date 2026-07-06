package com.difft.android.chat.media

import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.application
import com.difft.android.chat.util.FileDecryptionUtil
import com.difft.android.websocket.api.crypto.AttachmentCipherStreamUtil
import com.luck.picture.lib.entity.LocalMedia
import difft.android.messageserialization.model.Attachment

/**
 * Builds [LocalMedia] entries for the full-screen [com.luck.picture.lib.PictureSelector] preview.
 *
 * Whenever a ciphertext file (`<base>.encrypt`) exists, the media `path` points at
 * [EncryptedAttachmentProvider] so the preview's image engine (Glide) and its save/share actions
 * read decrypted bytes through the ContentResolver — no plaintext is materialised on disk, and the
 * read is immune to the send-upload race that deletes the plaintext copy.
 *
 * Everything else (video, legacy plaintext-only images) keeps the original
 * [LocalMedia.generateLocalMedia] path so existing behaviour is preserved.
 *
 * `realPath` is always set to the canonical plaintext base path so callers can locate the entry
 * (e.g. compute the initial preview position) regardless of how it is loaded.
 */
object AttachmentPreview {

    fun localMediaFor(messageId: String, attachment: Attachment): LocalMedia {
        val fileName = attachment.fileName ?: ""
        val basePath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName

        // Prefer the decrypting `content://` provider whenever a ciphertext file exists — even if a
        // plaintext copy also exists transiently. During a fresh self-send the plaintext and the
        // `.encrypt` coexist while uploading, then PushTextSendJob deletes the plaintext the instant
        // upload completes. PictureSelector's [LocalMedia.generateLocalMedia] eagerly opens the
        // plaintext (getImageSize), so gating on "plaintext absent" would lose that race and throw
        // FileNotFoundException. Reading via the provider always targets the `.encrypt` and is immune
        // to the deletion (a still-incomplete ciphertext just fails the MAC → transient blank, never
        // a crash). Only legacy plaintext-only attachments and videos (no ciphertext) fall through.
        if (!EncryptedAttachmentAccess.encryptedFile(basePath).exists()) {
            return LocalMedia.generateLocalMedia(application, basePath)
        }

        val uri = EncryptedAttachmentAccess.contentUri(messageId, fileName)
        return LocalMedia.create().apply {
            path = uri.toString()
            realPath = basePath
            this.fileName = fileName
            mimeType = attachment.contentType
            width = attachment.width
            height = attachment.height
            size = runCatching {
                val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
                // Pad-aware exact length (matches the bytes the provider actually serves); fall back to
                // the block-floor estimate only if the key is missing / file is structurally invalid.
                FileDecryptionUtil.exactPlaintextLength(encFile, attachment.key)
                    .takeIf { it >= 0 }
                    ?: AttachmentCipherStreamUtil.getPlaintextLength(encFile.length())
            }.getOrDefault(0L)
        }
    }
}
