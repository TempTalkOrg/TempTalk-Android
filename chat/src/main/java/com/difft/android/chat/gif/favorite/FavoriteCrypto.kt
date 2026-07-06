package com.difft.android.chat.gif.favorite

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * favKey symmetric encryption for the favorites list blob (AES-256-GCM, JCE — no Ed25519,
 * no group AAD dependency). See android-impl-design.md §B1/§B3.2 and
 * cross-platform-alignment.md §2.5/§3.
 *
 * Wire format (byte-exact across Android / iOS / Mac):
 *   blob = Base64.NO_WRAP( nonce(12B) ‖ ciphertext ‖ tag(16B) )
 *   plaintext = UTF-8 JSON of [FavoriteListPlainJson] (ByteArray fields = Base64.NO_WRAP strings)
 *   AAD = "tt-fav-v1|gcm|list"
 * This release does NOT gzip (small payloads; encVersion=1 reserved for a future gzip bump).
 */
object FavoriteCrypto {

    const val FAV_KEY_SIZE = 32
    /** Encryption-scheme version this client writes (AES-256-GCM, AAD "tt-fav-v1"). The PUT must
     *  declare THIS (the version it encrypted with), never echo the server's stored value. */
    const val ENC_VERSION = 1
    private const val GCM_NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128 // 16-byte tag
    private const val AAD = "tt-fav-v1|gcm|list"

    private val gson = Gson()
    private val secureRandom = SecureRandom()

    /** Generate a new raw 32-byte AES-256 favKey via CSPRNG. */
    fun generateFavKey(): ByteArray = ByteArray(FAV_KEY_SIZE).also { secureRandom.nextBytes(it) }

    // ---- v2 key-wrapping: favKey wrapped under a KEK derived from the account identity ----
    // See design-v2-cross-platform.md §3.2. favKey (DEK) stays stable; KEK = HKDF(aci identity
    // private key). The wrapped favKey is stored server-side (opaque) so any device holding the
    // masterKey can derive the KEK and recover favKey — no per-device key distribution.

    /** HKDF info/salt/AAD — three-platform must-pin constants (must byte-match iOS/Mac). */
    private const val KEK_INFO = "tt-fav-kek-v1"
    private const val KEK_SALT = "tt-fav-kek-salt-v1"
    private const val WRAP_AAD = "tt-fav-wrap-v1"
    private const val WRAP_ENV_VERSION = 1
    private const val WRAP_ENV_KDF = "hkdf-sha256"
    private const val WRAP_ENV_ROOT = "master"
    private const val WRAP_ENV_CIPHER = "aes-256-gcm"

    /**
     * Derive the 32-byte KEK from the account identity private key (aci, 32-byte Curve25519 scalar).
     * RFC 5869 HKDF-SHA256 with the pinned salt/info so all platforms derive the same KEK.
     *
     * The scalar is Curve25519-CLAMPED before HKDF (b0 &= 0xF8; b31 &= 0x7F; b31 |= 0x40). Clamping
     * is idempotent (no-op on an already-clamped key), and it normalizes the byte representation so
     * the KEK is identical whether the platform's crypto lib stored the private key clamped
     * (libsignal) or as the raw seed (e.g. Curve25519Kit) — otherwise those 2 bytes would silently
     * diverge the KEK and break cross-platform unwrap. Three-platform must-pin.
     */
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

    /** Wrap [favKey] under [kek] into the self-describing envelope JSON string (stored server-side). */
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

    /** Unwrap the envelope [wrappedFavKey] with [kek] → raw favKey, or null on any failure. */
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

    /** favKey fingerprint (NOT the key): Base64.NO_WRAP of SHA-256(favKey)[0..7]. */
    fun keyId(favKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(favKey)
        return Base64.encodeToString(digest.copyOfRange(0, 8), Base64.NO_WRAP)
    }

    /** RFC 5869 HKDF-SHA256 (standard, so iOS/Mac match byte-for-byte). */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        // Extract
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand
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

    /**
     * Encrypt a favorites list into the Base64 blob. [favKey] must be 32 bytes.
     * Throws on a malformed key (caller treats as a hard error).
     */
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
        val ctWithTag = cipher.doFinal(plaintext) // ciphertext ‖ tag

        val out = ByteArray(nonce.size + ctWithTag.size)
        System.arraycopy(nonce, 0, out, 0, nonce.size)
        System.arraycopy(ctWithTag, 0, out, nonce.size, ctWithTag.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * Decrypt a Base64 blob back into a favorites list. Returns null on any failure
     * (bad key / tampered blob / malformed JSON) — the caller leaves history pending.
     */
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

    // -- JSON wire shape (ByteArray -> Base64.NO_WRAP string) for byte-exact cross-platform blob --

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
        // authorizeId is a snowflake id > 2^53. Serialize it as a STRING (not a JSON number) in the
        // cross-platform blob so peers that parse JSON numbers as IEEE-754 doubles (JS / Swift) don't
        // lose the low digits (e.g. ...514568 -> ...515000). Matches the server item meta, which is
        // also a String. A String field still reads a bare number token exactly, so this is
        // backward-tolerant of any older number-encoded blob.
        @SerializedName("authorizeId") val authorizeId: String? = null,
        @SerializedName("key") val key: String? = null,
        @SerializedName("digest") val digest: String? = null,
        @SerializedName("fileHash") val fileHash: String? = null,
        @SerializedName("contentType") val contentType: String? = null,
        @SerializedName("width") val width: Int = 0,
        @SerializedName("height") val height: Int = 0,
        // Plaintext asset size in bytes; 0 when absent (legacy blob). Cross-platform contract field.
        @SerializedName("size") val size: Int = 0
    )
}
