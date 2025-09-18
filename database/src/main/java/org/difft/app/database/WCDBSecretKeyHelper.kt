package org.difft.app.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


data class SealedData(
    val iv: ByteArray,
    val data: ByteArray
)

object WCDBSecretKeyHelper {

    private const val ANDROID_KEY_STORE: String = "AndroidKeyStore"
    private const val KEY_ALIAS: String = "WCDBSecret"
    private const val KEY_WCDB_SECRET_KEYNAME = "wcdb_secret_key"
    private const val KEY_WCDB_SECRET_STORETYPE = "wcdb_secret_type"
    private const val KEY_WCDB_SECRET_OBJ = "object"
    private const val KEY_WCDB_SECRET_STRING = "string"

    fun getOrCreateDBSecretKey(context: Context): ByteArray? {
        val wcdbSecureSharedPrefsUtil = WCDBSecureSharedPrefsUtil(context)
        val secretKeyData = wcdbSecureSharedPrefsUtil.getString(KEY_WCDB_SECRET_KEYNAME, null)
        val secretKeyStoreType = wcdbSecureSharedPrefsUtil.getString(KEY_WCDB_SECRET_STORETYPE, "")
        if(secretKeyData.isNullOrEmpty()){
            val secureRandom = SecureRandom()
            val keyBytes = ByteArray(48)
            secureRandom.nextBytes(keyBytes)
            var serializedKey: String
            try {
                val sealedData = seal(keyBytes) ?: throw Exception("Seal method returned null")
                serializedKey = Gson().toJson(sealedData)
                wcdbSecureSharedPrefsUtil.putString(KEY_WCDB_SECRET_KEYNAME, serializedKey)
                wcdbSecureSharedPrefsUtil.putString(KEY_WCDB_SECRET_STORETYPE, KEY_WCDB_SECRET_OBJ)
                return keyBytes
            } catch (e: Exception){
                serializedKey = Base64.encodeToString(keyBytes, Base64.NO_PADDING)
                wcdbSecureSharedPrefsUtil.putString(KEY_WCDB_SECRET_KEYNAME, serializedKey)
                wcdbSecureSharedPrefsUtil.putString(KEY_WCDB_SECRET_STORETYPE, KEY_WCDB_SECRET_STRING)
                return keyBytes
            }
        }else {
            return try {
                if(secretKeyStoreType == KEY_WCDB_SECRET_OBJ){
                    val sealedData = Gson().fromJson(secretKeyData, SealedData::class.java)
                    unseal(sealedData)
                }else{
                    Base64.decode(secretKeyData, Base64.NO_PADDING)
                }
            } catch (e: Exception){
                e.printStackTrace()
                null
            }
        }
    }


    private fun seal(input: ByteArray): SealedData? {
        val secretKey: SecretKey? = getOrCreateKeyStoreEntry()
        try {
            if (null != secretKey) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val data = cipher.doFinal(input)
                return SealedData(iv, data)
            } else {
                return null
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun unseal(sealedData: SealedData): ByteArray? {
        val secretKey: SecretKey? = getKeyStoreEntry()
        try {
            if (secretKey != null) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, sealedData.iv))
                return cipher.doFinal(sealedData.data)
            } else {
                return null
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return null
    }


    private fun getOrCreateKeyStoreEntry(): SecretKey? {
        return if (hasKeyStoreEntry()) getKeyStoreEntry()
        else createKeyStoreEntry()
    }

    private fun createKeyStoreEntry(): SecretKey? {
        try {
            val keyGenerator: KeyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
            )
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()

            keyGenerator.init(keyGenParameterSpec)

            return keyGenerator.generateKey()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return null
    }


    private fun hasKeyStoreEntry(): Boolean {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEY_STORE)
            ks.load(null)
            return ks.containsAlias(KEY_ALIAS) && ks.entryInstanceOf(
                KEY_ALIAS,
                KeyStore.SecretKeyEntry::class.java
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun getKeyStore(): KeyStore? {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
            keyStore.load(null)
            return keyStore
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun getKeyStoreEntry(): SecretKey? {
        val keyStore: KeyStore? = getKeyStore()
        try {
            return getSecretKey(keyStore)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return null
    }


    @Throws(UnrecoverableKeyException::class)
    private fun getSecretKey(keyStore: KeyStore?): SecretKey? {
        try {
            if (null != keyStore) {
                val entry =
                    keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
                return entry.secretKey
            }
            return null
        }
        catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return null
    }

}