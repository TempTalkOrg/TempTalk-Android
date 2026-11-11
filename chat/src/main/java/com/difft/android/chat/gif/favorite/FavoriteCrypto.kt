package com.difft.android.chat.gif.favorite

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec












object FavoriteCrypto {

    const val FAV_KEY_SIZE = 32
    

    const val ENC_VERSION = 1
    private const val GCM_NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128 
    private const val AAD = "tt-fav-v1|gcm|list"

    private val gson = Gson()
    private val secureRandom = SecureRandom()

    
    fun generateFavKey(): ByteArray = ByteArray(FAV_KEY_SIZE).also { secureRandom.nextBytes(it) }

    
    
    
    

    
    private const val KEK_INFO = "tt-fav-kek-v1"
    private const val KEK_SALT = "tt-fav-kek-salt-v1"
    private const val WRAP_AAD = "tt-fav-wrap-v1"
    private const val WRAP_ENV_VERSION = 1
    private const val WRAP_ENV_KDF = "hkdf-sha256"
    private const val WRAP_ENV_ROOT = "master"
    private const val WRAP_ENV_CIPHER = "aes-256-gcm"

    









    fun deriveKek(masterKeyPriv: ByteArray): ByteArray {
        val ikm = masterKeyPriv.copyOf()
        if (ikm.size == FAV_KEY_SIZE) {
            ikm[0] = (ikm[0].toInt() and 0xF8).toByte()
            ikm[31] = (ikm[31].toInt() and 0x7F).toByte()
            ikm[31] = (ikm[31].toInt() or 0x40).toByte()
        }
        return hkdfSha256(
            ikm = ikm,
            salt = KEK_SALT.toByteArray(Charsets.UTF_8),
            info = KEK_INFO.toByteArray(Charsets.UTF_8),
            length = FAV_KEY_SIZE
        )
    }

    
    fun wrapFavKey(kek: ByteArray, favKey: ByteArray): String {
        require(kek.size == FAV_KEY_SIZE) { "KEK must be $FAV_KEY_SIZE bytes" }
        val nonce = ByteArray(GCM_NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(WRAP_AAD.toByteArray(Charsets.UTF_8))
        val ctWithTag = cipher.doFinal(favKey)
        val out = ByteArray(nonce.size + ctWithTag.size)
        System.arraycopy(nonce, 0, out, 0, nonce.size)
        System.arraycopy(ctWithTag, 0, out, nonce.size, ctWithTag.size)
        val env = WrappedFavKeyEnvelope(
            v = WRAP_ENV_VERSION, kdf = WRAP_ENV_KDF, root = WRAP_ENV_ROOT,
            cipher = WRAP_ENV_CIPHER, ct = Base64.encodeToString(out, Base64.NO_WRAP)
        )
        return gson.toJson(env)
    }

    
    fun unwrapFavKey(kek: ByteArray, wrappedFavKey: String): ByteArray? {
        if (kek.size != FAV_KEY_SIZE) return null
        return try {
            val env = gson.fromJson(wrappedFavKey, WrappedFavKeyEnvelope::class.java) ?: return null
            val ct = env.ct ?: return null
            val raw = Base64.decode(ct, Base64.NO_WRAP)
            if (raw.size <= GCM_NONCE_SIZE) return null
            val nonce = raw.copyOfRange(0, GCM_NONCE_SIZE)
            val ctWithTag = raw.copyOfRange(GCM_NONCE_SIZE, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(WRAP_AAD.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ctWithTag)
        } catch (e: Exception) {
            L.w { "[FavoriteCrypto] unwrap failed: ${e.message}" }
            null
        }
    }

    
    fun keyId(favKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(favKey)
        return Base64.encodeToString(digest.copyOfRange(0, 8), Base64.NO_WRAP)
    }

    
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private data class WrappedFavKeyEnvelope(
        @SerializedName("v") val v: Int = WRAP_ENV_VERSION,
        @SerializedName("kdf") val kdf: String? = null,
        @SerializedName("root") val root: String? = null,
        @SerializedName("cipher") val cipher: String? = null,
        @SerializedName("ct") val ct: String? = null
    )

    



    fun encrypt(favKey: ByteArray, list: FavoriteListPlain): String {
        require(favKey.size == FAV_KEY_SIZE) { "favKey must be $FAV_KEY_SIZE bytes" }
        val plaintext = gson.toJson(list.toJson()).toByteArray(Charsets.UTF_8)

        val nonce = ByteArray(GCM_NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(favKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val ctWithTag = cipher.doFinal(plaintext) 

        val out = ByteArray(nonce.size + ctWithTag.size)
        System.arraycopy(nonce, 0, out, 0, nonce.size)
        System.arraycopy(ctWithTag, 0, out, nonce.size, ctWithTag.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    



    fun decrypt(favKey: ByteArray, blob: String): FavoriteListPlain? {
        if (favKey.size != FAV_KEY_SIZE) {
            L.w { "[FavoriteCrypto] decrypt skipped: bad favKey size=${favKey.size}" }
            return null
        }
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size <= GCM_NONCE_SIZE) return null
            val nonce = raw.copyOfRange(0, GCM_NONCE_SIZE)
            val ctWithTag = raw.copyOfRange(GCM_NONCE_SIZE, raw.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(favKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(ctWithTag)
            val json = gson.fromJson(String(plaintext, Charsets.UTF_8), FavoriteListPlainJson::class.java)
            json?.toDomain()
        } catch (e: Exception) {
            L.w { "[FavoriteCrypto] decrypt failed: ${e.stackTraceToString()}" }
            null
        }
    }

    

    private fun FavoriteListPlain.toJson() = FavoriteListPlainJson(
        records = records.map { r ->
            FavoriteRecordJson(
                attachment = FavoriteAttachmentPointerJson(
                    id = r.attachment.id,
                    authorizeId = r.attachment.authorizeId.toString(),
                    key = Base64.encodeToString(r.attachment.key, Base64.NO_WRAP),
                    digest = Base64.encodeToString(r.attachment.digest, Base64.NO_WRAP),
                    fileHash = r.attachment.fileHash,
                    contentType = r.attachment.contentType,
                    width = r.attachment.width,
                    height = r.attachment.height,
                    size = r.attachment.size
                ),
                addedListVersion = r.addedListVersion
            )
        }
    )

    private fun FavoriteListPlainJson.toDomain() = FavoriteListPlain(
        records = (records ?: emptyList()).mapNotNull { r ->
            val a = r.attachment ?: return@mapNotNull null
            val fileHash = a.fileHash ?: return@mapNotNull null
            FavoriteRecord(
                attachment = FavoriteAttachmentPointer(
                    id = a.id ?: "",
                    authorizeId = a.authorizeId?.toLongOrNull() ?: 0L,
                    key = a.key?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
                    digest = a.digest?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
                    fileHash = fileHash,
                    contentType = a.contentType ?: "image/webp",
                    width = a.width,
                    height = a.height,
                    size = a.size
                ),
                addedListVersion = r.addedListVersion
            )
        }
    )

    private data class FavoriteListPlainJson(
        @SerializedName("records") val records: List<FavoriteRecordJson>? = null
    )

    private data class FavoriteRecordJson(
        @SerializedName("attachment") val attachment: FavoriteAttachmentPointerJson? = null,
        @SerializedName("addedListVersion") val addedListVersion: Long = 0L
    )

    private data class FavoriteAttachmentPointerJson(
        @SerializedName("id") val id: String? = null,
        
        
        
        
        
        @SerializedName("authorizeId") val authorizeId: String? = null,
        @SerializedName("key") val key: String? = null,
        @SerializedName("digest") val digest: String? = null,
        @SerializedName("fileHash") val fileHash: String? = null,
        @SerializedName("contentType") val contentType: String? = null,
        @SerializedName("width") val width: Int = 0,
        @SerializedName("height") val height: Int = 0,
        
        @SerializedName("size") val size: Int = 0
    )
}
