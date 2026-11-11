package com.difft.android.network.auth

import java.util.Base64





internal fun testJwt(expiresInMs: Long, uid: String? = null): String =
    testJwtExpiringAt(System.currentTimeMillis() + expiresInMs, uid)





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
