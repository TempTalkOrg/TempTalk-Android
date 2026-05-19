package com.difft.android.chat.crypto

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import uniffi.dtproto.DtGroupCrypto

/**
 * Group metadata encryption operations.
 * Wraps dtproto 3.1.0 Rust library (UniFFI) with business-layer concerns
 * (ByteArray/List<UByte> conversion, Base64 encoding, SPKI DER wrapping).
 */
object GroupCrypto {

    private val gc = DtGroupCrypto(GroupCryptoConstants.BLOB_VERSION_V1)

    /**
     * Generate a new 32-byte root key using CSPRNG.
     */
    fun generateRGroup(): ByteArray {
        val rGroup = ByteArray(GroupCryptoConstants.R_GROUP_SIZE)
        java.security.SecureRandom().nextBytes(rGroup)
        return rGroup
    }

    /**
     * Derive AES-256-GCM key from R_group via HKDF.
     * Deterministic: same R_group always produces the same K_group.
     */
    fun deriveKGroup(rGroup: ByteArray): ByteArray {
        return gc.deriveKeys(rGroup.toUByteList()).kGroup.toByteArray()
    }

    /**
     * Derive Ed25519 private key (for signing member UIDs) from R_group.
     */
    fun deriveSkBind(rGroup: ByteArray): ByteArray {
        return gc.deriveKeys(rGroup.toUByteList()).skBind.toByteArray()
    }

    /**
     * Derive Ed25519 public key (for verifying member UIDs) from R_group.
     * Returns raw 32 bytes. Use [pkBindToSpkiBase64] for server upload format.
     */
    fun derivePkBind(rGroup: ByteArray): ByteArray {
        return gc.deriveKeys(rGroup.toUByteList()).pkBind.toByteArray()
    }

    /**
     * Encrypt group name with K_group.
     * @return Base64-encoded blob
     */
    fun encryptGroupName(kGroup: ByteArray, plainName: String): String {
        val blob = gc.encrypt(
            kGroup.toUByteList(),
            plainName.toByteArray(Charsets.UTF_8).toUByteList(),
            GroupCryptoConstants.GCM_AAD_GROUP_NAME.toByteArray(Charsets.UTF_8).toUByteList(),
        )
        return Base64.encodeToString(blob.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Decrypt group name. Returns null on failure.
     */
    fun decryptGroupName(kGroup: ByteArray, encryptedName: String): String? {
        return try {
            val blob = Base64.decode(encryptedName, Base64.DEFAULT)
            val plaintext = gc.decrypt(
                kGroup.toUByteList(),
                blob.toUByteList(),
                GroupCryptoConstants.GCM_AAD_GROUP_NAME.toByteArray(Charsets.UTF_8).toUByteList()
            )
            String(plaintext.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            L.e { "[GroupCrypto] decryptGroupName failed: ${e.message}" }
            null
        }
    }

    /**
     * Encrypt group avatar metadata (avatar JSON) with K_group.
     * @return Base64-encoded blob
     */
    fun encryptGroupAvatar(kGroup: ByteArray, plainAvatar: String): String {
        val blob = gc.encrypt(
            kGroup.toUByteList(),
            plainAvatar.toByteArray(Charsets.UTF_8).toUByteList(),
            GroupCryptoConstants.GCM_AAD_GROUP_AVATAR.toByteArray(Charsets.UTF_8).toUByteList(),
        )
        return Base64.encodeToString(blob.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Decrypt group avatar metadata. Returns null on failure.
     */
    fun decryptGroupAvatar(kGroup: ByteArray, encryptedAvatar: String): String? {
        return try {
            val blob = Base64.decode(encryptedAvatar, Base64.DEFAULT)
            val plaintext = gc.decrypt(
                kGroup.toUByteList(),
                blob.toUByteList(),
                GroupCryptoConstants.GCM_AAD_GROUP_AVATAR.toByteArray(Charsets.UTF_8).toUByteList()
            )
            String(plaintext.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            L.e { "[GroupCrypto] decryptGroupAvatar failed: ${e.message}" }
            null
        }
    }

    /**
     * Sign a member UID with Ed25519 private key.
     * Rust internally signs: "tt-grp-v1|ed25519|uid-binding|{uid}"
     * @return Base64-encoded 64-byte signature
     */
    fun signUid(skBind: ByteArray, uid: String): String {
        val signature = gc.signUid(skBind.toUByteList(), uid)
        return Base64.encodeToString(signature.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Verify a member UID signature with Ed25519 public key.
     */
    fun verifyUid(pkBind: ByteArray, uid: String, uidSignature: String): Boolean {
        return try {
            val signature = Base64.decode(uidSignature, Base64.DEFAULT)
            gc.verifyUid(pkBind.toUByteList(), uid, signature.toUByteList())
        } catch (e: Exception) {
            L.e { "[GroupCrypto] verifyUid failed: ${e.message}" }
            false
        }
    }

    /**
     * Wrap raw 32-byte Ed25519 public key to X.509 SPKI DER Base64 format for server upload.
     */
    fun pkBindToSpkiBase64(pkBind: ByteArray): String {
        require(pkBind.size == 32) { "Ed25519 public key must be 32 bytes" }
        val spki = GroupCryptoConstants.ED25519_SPKI_PREFIX + pkBind
        return Base64.encodeToString(spki, Base64.NO_WRAP)
    }

    // -- Conversion helpers (ByteArray <-> List<UByte>) --

    private fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }

    private fun List<UByte>.toByteArray(): ByteArray = map { it.toByte() }.toByteArray()
}
