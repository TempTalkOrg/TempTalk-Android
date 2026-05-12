package com.difft.android.network.responses

/**
 * Encrypted global config response from CDN.
 *
 * Format:
 * ```json
 * {
 *   "code": 0,
 *   "keyId": "key-2025a",
 *   "data": "Base64(AES-256-GCM ciphertext || 16-byte AuthTag)",
 *   "nonce": "Base64(32-byte random)",
 *   "sign": "GLOBALCONFIG_Base64(ECDSA-SHA256 signature)"
 * }
 * ```
 */
data class EncryptedGlobalConfigResponse(
    val code: Int = 0,
    val keyId: String? = null,
    val data: String? = null,
    val nonce: String? = null,
    val sign: String? = null
)