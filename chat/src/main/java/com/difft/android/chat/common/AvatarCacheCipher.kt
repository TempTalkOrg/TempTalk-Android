package com.difft.android.chat.common

import com.difft.android.base.glide.EncryptedCacheCoder
import com.difft.android.base.glide.GlideCacheKeyManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream





















object AvatarCacheCipher {

    private const val TAG = "[AvatarCacheCipher]"

    




    @Volatile
    internal var masterKeyProvider: () -> ByteArray? = { GlideCacheKeyManager.getKeyOrNull(application) }

    








    @Throws(IOException::class)
    fun writeEncrypted(file: File, plainBytes: ByteArray) {
        val dir = file.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
        try {
            val masterKey = masterKeyProvider()
            if (masterKey != null) {
                EncryptedCacheCoder(masterKey).encryptedOutput(tmp).use { it.write(plainBytes) }
            } else {
                
                tmp.outputStream().use { it.write(plainBytes); it.flush() }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("rename ${tmp.name} -> ${file.name} failed")
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            throw if (e is IOException) e else IOException(e)
        }
    }

    






    @Throws(IOException::class)
    fun openDecrypting(file: File): InputStream {
        if (!EncryptedCacheCoder.hasMagic(file)) {
            return FileInputStream(file) 
        }
        val masterKey = masterKeyProvider()
            ?: throw IOException("$TAG keystore unavailable for encrypted avatar ${file.name}")
        return EncryptedCacheCoder(masterKey).encryptedInput(file)
    }

    




    fun plaintextLength(file: File): Long = try {
        if (EncryptedCacheCoder.hasMagic(file)) {
            (file.length() - EncryptedCacheCoder.HEADER_OVERHEAD).coerceAtLeast(0L)
        } else {
            file.length()
        }
    } catch (e: Exception) {
        L.w { "$TAG plaintextLength failed: ${e.message}" }
        0L
    }
}
