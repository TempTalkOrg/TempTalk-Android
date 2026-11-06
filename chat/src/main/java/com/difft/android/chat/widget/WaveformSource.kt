package com.difft.android.chat.widget

import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.util.FileDecryptionUtil
import java.io.File

/**
 * The file a voice-message waveform is extracted from.
 *
 * A ciphertext is decrypted (MAC first) into a temp under [tempDir], an internal cache dir, never
 * next to its `.encrypt`: the attachment dir is external storage on many devices, readable by other
 * apps on Android 8–10 (#1438). Without a usable key the plaintext at the attachment path is read in
 * place: a legacy file, or a self-sent voice message still uploading (its key is stored only after
 * the upload).
 */
internal object WaveformSource {

    const val TEMP_DIR_NAME = "waveform_tmp"

    class Source(val file: File, val isTemp: Boolean)

    /**
     * @return the file to read, or null when neither a decryptable ciphertext nor a plaintext exists.
     * @throws Exception when the ciphertext fails its MAC or cannot be decrypted (no temp is left).
     */
    fun open(attachmentPath: String, key: ByteArray?, tempDir: File): Source? {
        val plaintext = File(attachmentPath)
        if (EncryptedAttachmentAccess.isUsableKey(key) && EncryptedAttachmentAccess.hasEncrypted(attachmentPath)) {
            tempDir.mkdirs()
            val temp = File.createTempFile("waveform", ".tmp", tempDir)
            try {
                FileDecryptionUtil.decryptFile(EncryptedAttachmentAccess.encryptedFile(attachmentPath), temp, key)
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
            // The ciphertext just passed its MAC, so a plaintext beside it can only be a decrypted copy
            // left by an older extraction that failed: drop it so it does not stay readable.
            if (plaintext.exists()) plaintext.delete()
            return Source(temp, isTemp = true)
        }
        return if (plaintext.exists()) Source(plaintext, isTemp = false) else null
    }

    /**
     * Deletes temps older than [staleBeforeMs]: a process killed mid-extraction never ran [use]'s
     * cleanup. Newer files may belong to an extraction already running in this process.
     */
    fun clearStale(tempDir: File, staleBeforeMs: Long): Int =
        tempDir.listFiles().orEmpty().count { it.isFile && it.lastModified() < staleBeforeMs && it.delete() }

    /** Runs [block] on the source and deletes a decrypted temp afterwards, whatever the outcome. */
    inline fun <T> use(source: Source, block: (File) -> T): T =
        try {
            block(source.file)
        } finally {
            if (source.isTemp) source.file.delete()
        }
}
