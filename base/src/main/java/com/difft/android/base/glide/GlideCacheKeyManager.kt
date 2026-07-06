package com.difft.android.base.glide

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides the master key used to encrypt Glide's on-disk RESOURCE cache (see [EncryptedCacheCoder]).
 *
 * The 32-byte master key is generated once, sealed with an AndroidKeyStore AES/GCM key, and stored at
 * `filesDir/glide_cache_key.bin`. This mirrors the proven approach of
 * `org.difft.app.database.WCDBKeyManager`, but is intentionally simpler: a single 32-byte secret, no
 * legacy migration. It is deliberately a separate Keystore alias from the WCDB and attachment keys.
 *
 * Failure policy: if the Keystore is unavailable (locked/reset/unsupported), [getKeyOrNull] returns
 * null and the caller transparently disables the encrypted cache (falls back to no disk cache). The
 * cache is a performance optimization, never a correctness dependency, so we never throw here.
 */
object GlideCacheKeyManager {

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "GlideCacheKey"

    private const val KEY_FILE_NAME = "glide_cache_key.bin"
    private const val KEY_FILE_TMP_NAME = "glide_cache_key.bin.tmp"

    private const val PLAINTEXT_KEY_SIZE = 32 // used as the HMAC-SHA256 master key
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8 // 16
    private const val WIRE_FORMAT_SIZE = GCM_IV_SIZE + PLAINTEXT_KEY_SIZE + GCM_TAG_BYTES // 60

    @Volatile
    private var cached: ByteArray? = null

    /** @return the 32-byte cache master key, or null when the Keystore is unavailable. */
    fun getKeyOrNull(context: Context): ByteArray? {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            try {
                loadOrCreate(context.applicationContext).also { cached = it }
            } catch (e: Throwable) {
                L.w(e) { "[GlideCacheKey] key unavailable, encrypted cache disabled" }
                null
            }
        }
    }

    fun isAvailable(context: Context): Boolean = getKeyOrNull(context) != null

    private fun loadOrCreate(context: Context): ByteArray {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        if (keyFile.exists()) {
            try {
                return readAndDecrypt(keyFile)
            } catch (e: Throwable) {
                // Key file present but unreadable (Keystore alias rotated/removed, or a corrupt blob).
                // Unlike a database key, the cache key is disposable: self-heal by regenerating. Old
                // cache entries then fail to decrypt → cache miss → re-decoded and re-encrypted under
                // the new key. (Contrast WCDBKeyManager, which must NEVER regenerate.)
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
        // AES/GCM appends the auth tag to the ciphertext. Wire = iv || ciphertext || tag.
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
