package com.difft.android.base.glide

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec













object GlideCacheKeyManager {

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "GlideCacheKey"

    private const val KEY_FILE_NAME = "glide_cache_key.bin"
    private const val KEY_FILE_TMP_NAME = "glide_cache_key.bin.tmp"

    private const val PLAINTEXT_KEY_SIZE = 32 
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8 
    private const val WIRE_FORMAT_SIZE = GCM_IV_SIZE + PLAINTEXT_KEY_SIZE + GCM_TAG_BYTES 

    @Volatile
    private var cached: ByteArray? = null

    
    
    
    
    private const val STATE_UNKNOWN = 0
    private const val STATE_READY = 1
    private const val STATE_UNAVAILABLE = 2

    @Volatile
    private var warmState = STATE_UNKNOWN

    private val warming = AtomicBoolean(false)

    





    fun getKeyOrNull(context: Context): ByteArray? {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            try {
                loadOrCreate(context.applicationContext).also {
                    cached = it
                    warmState = STATE_READY
                }
            } catch (e: Throwable) {
                
                
                warmState = STATE_UNAVAILABLE
                L.w(e) { "[GlideCacheKey] key unavailable, encrypted cache disabled" }
                null
            }
        }
    }

    




    fun isAvailable(context: Context): Boolean = getKeyOrNull(context) != null

    








    fun isCacheKeyReady(context: Context): Boolean = when (warmState) {
        STATE_READY -> true
        STATE_UNAVAILABLE -> false
        else -> {
            warmUp(context)
            false
        }
    }

    
    fun warmUp(context: Context) {
        if (warmState != STATE_UNKNOWN) return
        if (!warming.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        appScope.launch {
            try {
                getKeyOrNull(appContext)
            } finally {
                warming.set(false)
            }
        }
    }

    private fun loadOrCreate(context: Context): ByteArray {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        if (keyFile.exists()) {
            try {
                return readAndDecrypt(keyFile)
            } catch (e: Throwable) {
                
                
                
                
                L.w(e) { "[GlideCacheKey] key file unreadable, regenerating (old cache invalidated)" }
            }
        }

        val fresh = ByteArray(PLAINTEXT_KEY_SIZE).also { SecureRandom().nextBytes(it) }
        writeAtomic(context, fresh)
        L.i { "[GlideCacheKey] generated and persisted fresh cache key" }
        return fresh
    }

    private fun readAndDecrypt(keyFile: File): ByteArray {
        val blob = keyFile.readBytes()
        require(blob.size == WIRE_FORMAT_SIZE) { "corrupt $KEY_FILE_NAME size=${blob.size}" }

        val iv = blob.copyOfRange(0, GCM_IV_SIZE)
        val ciphertextAndTag = blob.copyOfRange(GCM_IV_SIZE, WIRE_FORMAT_SIZE)

        val secretKey = getKeystoreEntry() ?: error("Keystore alias '$KEY_ALIAS' missing while key file exists")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertextAndTag)
        require(plaintext.size == PLAINTEXT_KEY_SIZE) { "decrypted key wrong length=${plaintext.size}" }
        return plaintext
    }

    private fun writeAtomic(context: Context, plaintext: ByteArray) {
        val blob = encrypt(plaintext)
        val tempFile = File(context.filesDir, KEY_FILE_TMP_NAME)
        val finalFile = File(context.filesDir, KEY_FILE_NAME)
        try {
            tempFile.outputStream().use { os ->
                os.write(blob)
                os.flush()
                os.fd.sync()
            }
            if (!tempFile.renameTo(finalFile)) {
                finalFile.delete()
                if (!tempFile.renameTo(finalFile)) error("rename $KEY_FILE_TMP_NAME -> $KEY_FILE_NAME failed")
            }
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val secretKey = getOrCreateKeystoreEntry() ?: error("could not get or create Keystore alias '$KEY_ALIAS'")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        val iv = cipher.iv
        require(iv.size == GCM_IV_SIZE) { "unexpected IV size=${iv.size}" }
        
        return iv + cipher.doFinal(plaintext)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun getOrCreateKeystoreEntry(): SecretKey? {
        getKeystoreEntry()?.let { return it }
        return try {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            keyGenerator.generateKey()
        } catch (e: Throwable) {
            L.e(e) { "[GlideCacheKey] failed to create Keystore alias '$KEY_ALIAS'" }
            null
        }
    }

    private fun getKeystoreEntry(): SecretKey? = try {
        val ks = keyStore()
        if (!ks.containsAlias(KEY_ALIAS)) null
        else (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: Throwable) {
        L.w(e) { "[GlideCacheKey] failed to load Keystore alias '$KEY_ALIAS'" }
        null
    }
}
