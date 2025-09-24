package com.difft.android.network.responses

data class SSODomainCheckResponse(
    val domain: String?,
    val issuer: String?,
    val clientId: String?,
    val audience: String?,
)