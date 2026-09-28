package com.difft.android.network.auth

import java.util.Base64

/**
 * Minimal unsigned JWT with the claims the auth layer reads. Signature is never verified here —
 * `com.auth0.android.jwt.JWT` only decodes — so a fixed placeholder is enough.
 */
internal fun testJwt(expiresInMs: Long, uid: String? = null): String =
    testJwtExpiringAt(System.currentTimeMillis() + expiresInMs, uid)

/**
 * Minimal unsigned JWT expiring at [atMs]. `exp` has second resolution, so [atMs] is truncated
 * down to a whole second — pass a multiple of 1000 when the exact instant matters.
 */
internal fun testJwtExpiringAt(atMs: Long, uid: String? = null): String {
    val exp = atMs / 1000
    val claims = buildString {
        append("""{"exp":$exp""")
        if (uid != null) append(""","uid":"$uid"""")
        append("}")
    }
    return "${b64(HEADER)}.${b64(claims)}.sig"
}

private const val HEADER = """{"alg":"none","typ":"JWT"}"""

private fun b64(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
