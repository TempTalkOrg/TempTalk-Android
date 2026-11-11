package com.difft.android.network.responses















data class EncryptedGlobalConfigResponse(
    val code: Int = 0,
    val keyId: String? = null,
    val data: String? = null,
    val nonce: String? = null,
    val sign: String? = null
)