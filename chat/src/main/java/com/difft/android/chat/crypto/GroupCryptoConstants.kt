package com.difft.android.chat.crypto

/**
 * Constants for group metadata encryption.
 * These values MUST be identical across all platforms (Android, iOS, server).
 *
 * HKDF and signature prefix constants are handled internally by the Rust library (derive_keys / sign_uid).
 * Only GCM AAD constants need to be passed by the calling layer (encrypt / decrypt).
 */
object GroupCryptoConstants {
    // GCM Additional Authenticated Data — passed to encrypt/decrypt by calling layer
    // Any mismatch across platforms will cause decryption failure
    const val GCM_AAD_GROUP_NAME = "tt-grp-v1|gcm|name"
    const val GCM_AAD_GROUP_AVATAR = "tt-grp-v1|gcm|avatar"

    // Ciphertext blob version — passed to encrypt by calling layer
    const val BLOB_VERSION_V1: UByte = 1u

    // Key sizes (for validation)
    const val R_GROUP_SIZE = 32
    const val K_GROUP_SIZE = 32
    const val GCM_NONCE_SIZE = 12
    const val GCM_TAG_SIZE = 16
    const val ED25519_SIGNATURE_SIZE = 64

    // Ed25519 SPKI DER prefix (RFC 8410) — for wrapping raw 32-byte pk_bind before uploading to server
    // SEQUENCE { SEQUENCE { OID(1.3.101.112) }, BIT STRING(public_key) }
    val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2A,                                    // SEQUENCE (42 bytes)
        0x30, 0x05,                                    // SEQUENCE (5 bytes) — AlgorithmIdentifier
        0x06, 0x03, 0x2B, 0x65, 0x70,                  //   OID 1.3.101.112 (Ed25519)
        0x03, 0x21, 0x00                               // BIT STRING (33 bytes, 0 unused bits)
    )
}
