package com.difft.android.chat.widget

import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.util.FileDecryptionUtil
import java.io.File










internal object WaveformSource {

    const val TEMP_DIR_NAME = "waveform_tmp"

    class Source(val file: File, val isTemp: Boolean)

    



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
            
            
            if (plaintext.exists()) plaintext.delete()
            return Source(temp, isTemp = true)
        }
        return if (plaintext.exists()) Source(plaintext, isTemp = false) else null
    }

    



    fun clearStale(tempDir: File, staleBeforeMs: Long): Int =
        tempDir.listFiles().orEmpty().count { it.isFile && it.lastModified() < staleBeforeMs && it.delete() }

    
    inline fun <T> use(source: Source, block: (File) -> T): T =
        try {
            block(source.file)
        } finally {
            if (source.isTemp) source.file.delete()
        }
}
