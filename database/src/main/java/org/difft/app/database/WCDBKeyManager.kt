@file:Suppress("DEPRECATION") 
                              

package org.difft.app.database

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import com.difft.android.database.BuildConfig
import com.difft.android.base.utils.globalServices
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec





internal data class SealedData(
    val iv: ByteArray,
    val data: ByteArray
)






internal sealed interface KeystoreEntryResult {
    data class Found(val key: SecretKey) : KeystoreEntryResult
    data object Absent : KeystoreEntryResult
    data class Failed(val cause: Throwable) : KeystoreEntryResult
}












object WCDBKeyManager {

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"

    
    private const val KEY_ALIAS = "WCDBKey"

    
    private const val LEGACY_KEY_ALIAS = "WCDBSecret"

    
    private const val LEGACY_SP_NAME = "wcdb_secure_prefs"

    
    private const val LEGACY_KEY_DATA = "wcdb_secret_key"
    private const val LEGACY_KEY_TYPE = "wcdb_secret_type"
    private const val LEGACY_TYPE_OBJECT = "object"
    private const val LEGACY_TYPE_STRING = "string"

    
    private const val PLAINTEXT_KEY_SIZE = 48
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8 
    private const val WIRE_FORMAT_SIZE = GCM_IV_SIZE + PLAINTEXT_KEY_SIZE + GCM_TAG_BYTES 

    private const val KEY_FILE_NAME = "wcdb_key.bin"
    private const val KEY_FILE_TMP_NAME = "wcdb_key.bin.tmp"

    








    fun cipherKeyForCurrentBuild(context: Context): ByteArray? =
        if (!BuildConfig.DEBUG) getOrCreateKey(context) else null

    








    fun getOrCreateKey(context: Context): ByteArray {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        if (keyFile.exists()) {
            return readAndDecrypt(keyFile)
        }

        
        
        
        
        
        
        
        val migrated = tryMigrateFromLegacy(context)
        if (migrated != null) {
            writeAtomic(context, migrated)
            L.i { "[WCDBKeyManager] migrated WCDB key from legacy SP to wcdb_key.bin (legacy SP retained as backup)" }
            return migrated
        }

        val fresh = ByteArray(PLAINTEXT_KEY_SIZE).also { SecureRandom().nextBytes(it) }
        writeAtomic(context, fresh)
        L.i { "[WCDBKeyManager] generated and persisted fresh WCDB key" }
        return fresh
    }

    








    fun installKey(context: Context, key: ByteArray) {
        require(key.size == PLAINTEXT_KEY_SIZE) {
            "WCDB cipher key must be $PLAINTEXT_KEY_SIZE bytes; got ${key.size}"
        }
        writeAtomic(context, key)
        L.i { "[WCDBKeyManager] installed WCDB key via installKey (size=${key.size})" }
    }

    




    fun protectKey(plaintext: ByteArray): ByteArray {
        require(plaintext.size == PLAINTEXT_KEY_SIZE) {
            "key to protect must be $PLAINTEXT_KEY_SIZE bytes; got ${plaintext.size}"
        }
        return encrypt(plaintext)
    }

    






    fun unprotectKey(blob: ByteArray): ByteArray {
        if (blob.size != WIRE_FORMAT_SIZE) {
            throw WCDBKeyUnavailableException(
                "Corrupt protected key blob — expected $WIRE_FORMAT_SIZE bytes, got ${blob.size}"
            )
        }
        val iv = blob.copyOfRange(0, GCM_IV_SIZE)
        val ciphertextAndTag = blob.copyOfRange(GCM_IV_SIZE, WIRE_FORMAT_SIZE)
        
        
        val secretKey = when (val entry = getKeystoreEntry(KEY_ALIAS)) {
            is KeystoreEntryResult.Found -> entry.key
            KeystoreEntryResult.Absent -> throw WCDBKeyUnavailableException(
                "Keystore alias '$KEY_ALIAS' missing while unprotecting key"
            )
            is KeystoreEntryResult.Failed -> throw WCDBKeyUnavailableException(
                "Keystore read failed for alias '$KEY_ALIAS' while unprotecting key", entry.cause
            )
        }
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertextAndTag)
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException("Failed to decrypt protected key blob", e)
        }
        if (plaintext.size != PLAINTEXT_KEY_SIZE) {
            throw WCDBKeyUnavailableException(
                "Unprotected key wrong length — expected $PLAINTEXT_KEY_SIZE, got ${plaintext.size}"
            )
        }
        return plaintext
    }

    

    private fun readAndDecrypt(keyFile: File): ByteArray {
        val blob = try {
            keyFile.readBytes()
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException(
                "Failed to read $KEY_FILE_NAME (size=${keyFile.length()})", e
            )
        }
        if (blob.size != WIRE_FORMAT_SIZE) {
            throw WCDBKeyUnavailableException(
                "Corrupt $KEY_FILE_NAME — expected $WIRE_FORMAT_SIZE bytes, got ${blob.size}"
            )
        }
        val iv = blob.copyOfRange(0, GCM_IV_SIZE)
        val ciphertextAndTag = blob.copyOfRange(GCM_IV_SIZE, WIRE_FORMAT_SIZE)

        
        val secretKey = when (val entry = getKeystoreEntry(KEY_ALIAS)) {
            is KeystoreEntryResult.Found -> entry.key
            KeystoreEntryResult.Absent -> throw WCDBKeyUnavailableException(
                "Keystore alias '$KEY_ALIAS' missing (clean-absent) while key file exists"
            )
            is KeystoreEntryResult.Failed -> throw WCDBKeyUnavailableException(
                "Keystore read failed for alias '$KEY_ALIAS' while key file exists", entry.cause
            )
        }

        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertextAndTag)
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException("Failed to decrypt $KEY_FILE_NAME", e)
        }
        if (plaintext.size != PLAINTEXT_KEY_SIZE) {
            throw WCDBKeyUnavailableException(
                "Decrypted key wrong length — expected $PLAINTEXT_KEY_SIZE, got ${plaintext.size}"
            )
        }
        return plaintext
    }

    

    






    private fun writeAtomic(context: Context, plaintext: ByteArray) {
        require(plaintext.size == PLAINTEXT_KEY_SIZE) {
            "plaintext must be $PLAINTEXT_KEY_SIZE bytes; got ${plaintext.size}"
        }
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
                if (!tempFile.renameTo(finalFile)) {
                    throw WCDBKeyUnavailableException("Failed to rename $KEY_FILE_TMP_NAME → $KEY_FILE_NAME")
                }
            }
        } catch (e: Throwable) {
            tempFile.delete()
            if (e is WCDBKeyUnavailableException) throw e
            throw WCDBKeyUnavailableException("Atomic write of $KEY_FILE_NAME failed", e)
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val secretKey = when (val entry = getOrCreateKeystoreEntry(KEY_ALIAS)) {
            is KeystoreEntryResult.Found -> entry.key
            is KeystoreEntryResult.Failed -> throw WCDBKeyUnavailableException(
                "Could not get or create Keystore alias '$KEY_ALIAS'", entry.cause
            )
            KeystoreEntryResult.Absent -> throw WCDBKeyUnavailableException(
                "Keystore alias '$KEY_ALIAS' still absent after create attempt"
            )
        }
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException("Failed to initialize AES/GCM cipher", e)
        }
        val iv = cipher.iv
        if (iv.size != GCM_IV_SIZE) {
            throw WCDBKeyUnavailableException("Unexpected IV size: ${iv.size} (expected $GCM_IV_SIZE)")
        }
        val ciphertextAndTag = try {
            cipher.doFinal(plaintext)
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException("AES/GCM encrypt failed", e)
        }
        if (ciphertextAndTag.size != PLAINTEXT_KEY_SIZE + GCM_TAG_BYTES) {
            throw WCDBKeyUnavailableException(
                "Unexpected ciphertext+tag size: ${ciphertextAndTag.size}"
            )
        }
        
        return iv + ciphertextAndTag
    }

    

    

















    private fun tryMigrateFromLegacy(context: Context): ByteArray? {
        
        
        
        val legacySpFile = File(context.dataDir, "shared_prefs/$LEGACY_SP_NAME.xml")
        if (!legacySpFile.exists()) {
            return null
        }

        
        
        val prefs = try {
            openLegacyPrefs(context)
        } catch (e: Throwable) {
            throw WCDBKeyUnavailableException(
                "legacy SP file present but unreadable (Keystore/MasterKey failure?)", e
            )
        }

        
        
        val serializedKey = readLegacyPrefValue(prefs, LEGACY_KEY_DATA)
        if (serializedKey.isNullOrEmpty()) return null

        val type = readLegacyPrefValue(prefs, LEGACY_KEY_TYPE)

        
        val plaintext: ByteArray = try {
            when {
                type.isNullOrEmpty() -> {
                    
                    decodeLegacyStringOrThrow(serializedKey)
                }
                type == LEGACY_TYPE_OBJECT -> {
                    val sealed = globalServices.gson.fromJson(serializedKey, SealedData::class.java)
                        ?: throw WCDBKeyUnavailableException("legacy SealedData JSON parse returned null")
                    unsealLegacy(sealed)
                        ?: throw WCDBKeyUnavailableException("legacy SealedData unseal failed (alias='$LEGACY_KEY_ALIAS')")
                }
                type == LEGACY_TYPE_STRING -> decodeLegacyStringOrThrow(serializedKey)
                else -> throw WCDBKeyUnavailableException("legacy SP: unknown type='$type'")
            }
        } catch (e: WCDBKeyUnavailableException) {
            L.e { "[WCDBKeyManager] legacy decode failed (type=$type): ${e.stackTraceToString()}" }
            throw e
        } catch (e: Throwable) {
            L.e { "[WCDBKeyManager] legacy decode failed (type=$type): ${e.stackTraceToString()}" }
            throw WCDBKeyUnavailableException("legacy WCDB key decode failed (type=$type)", e)
        }

        if (plaintext.size != PLAINTEXT_KEY_SIZE) {
            throw WCDBKeyUnavailableException(
                "legacy decoded key wrong length=${plaintext.size} (expected $PLAINTEXT_KEY_SIZE)"
            )
        }
        return plaintext
    }

    




    private fun decodeLegacyStringOrThrow(serialized: String): ByteArray = try {
        Base64.decode(serialized, Base64.NO_PADDING)
    } catch (e: Throwable) {
        throw WCDBKeyUnavailableException("legacy Base64 decode failed", e)
    }

    
    private fun unsealLegacy(sealed: SealedData?): ByteArray? {
        if (sealed == null) return null
        
        val secretKey = (getKeystoreEntry(LEGACY_KEY_ALIAS) as? KeystoreEntryResult.Found)?.key ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, sealed.iv))
            cipher.doFinal(sealed.data)
        } catch (e: Throwable) {
            L.w { "[WCDBKeyManager] legacy unseal failed: ${e.javaClass.simpleName}" }
            null
        }
    }

    




    private fun openLegacyPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            LEGACY_SP_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    
    private fun readLegacyPrefValue(prefs: SharedPreferences, key: String): String? = try {
        if (prefs.contains(key)) prefs.getString(key, null) else null
    } catch (e: Throwable) {
        L.w { "[WCDBKeyManager] legacy SP read failed key=$key: ${e.javaClass.simpleName}" }
        null
    }

    

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    
    
    private fun getOrCreateKeystoreEntry(alias: String): KeystoreEntryResult {
        val existing = getKeystoreEntry(alias)
        if (existing is KeystoreEntryResult.Found) return existing
        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
            )
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            KeystoreEntryResult.Found(keyGenerator.generateKey())
        } catch (e: Throwable) {
            L.e { "[WCDBKeyManager] failed to create Keystore alias '$alias': ${e.stackTraceToString()}" }
            
            (existing as? KeystoreEntryResult.Failed)?.cause?.let { e.addSuppressed(it) }
            KeystoreEntryResult.Failed(e)
        }
    }

    internal fun getKeystoreEntry(alias: String): KeystoreEntryResult = try {
        val ks = keyStore()
        if (!ks.containsAlias(alias)) return KeystoreEntryResult.Absent
        val entry = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            ?: return KeystoreEntryResult.Absent
        KeystoreEntryResult.Found(entry.secretKey)
    } catch (e: Throwable) {
        L.w { "[WCDBKeyManager] failed to load Keystore alias '$alias': ${e.javaClass.simpleName}" }
        KeystoreEntryResult.Failed(e)
    }
}
