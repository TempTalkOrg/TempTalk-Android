package com.difft.android.network.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AuthStyleResolver] resolution order, and the two framework facts the design leans on: Retrofit
 * tags every request with an `Invocation`, and `Request.newBuilder()` preserves that tag across the
 * host-failover rebuild (issue #1231).
 */
class AuthStyleResolverTest {

    // T21 — a method declaration beats its interface's.
    @Test
    fun `method annotation wins over interface annotation`() {
        val request = requestOf(BasicDeclaringService::class.java) { it.overridesWithJwt() }

        assertEquals(AuthStyle.JWT, AuthStyleResolver.styleFor(request))
    }

    // T22 — an undeclared method inherits its interface's declaration.
    @Test
    fun `interface annotation applies to an undeclared method`() {
        val request = requestOf(BasicDeclaringService::class.java) { it.inheritsInterface() }

        assertEquals(AuthStyle.BASIC, AuthStyleResolver.styleFor(request))
    }

    // T23 — the fail-safe trigger: nothing declared, or no Invocation tag at all.
    @Test
    fun `returns null when nothing declares a style`() {
        val undeclared = requestOf(UndeclaredService::class.java) { it.undeclared() }
        val untagged = Request.Builder().url(BASE_URL).build()

        assertNull(AuthStyleResolver.styleFor(undeclared))
        assertNull(AuthStyleResolver.styleFor(untagged))
    }

    @Test
    fun `the declaration survives a host rewrite through newBuilder`() {
        val request = requestOf(JwtService::class.java) { it.token() }
        val rehosted = request.newBuilder()
            .url(request.url.newBuilder().host("backup.test.invalid").build())
            .build()

        assertEquals(AuthStyle.JWT, AuthStyleResolver.styleFor(rehosted))
    }

    @Test
    fun `describe names the endpoint as a code symbol`() {
        val request = requestOf(JwtService::class.java) { it.token() }

        assertEquals("JwtService.token", AuthStyleResolver.describe(request))
        assertEquals(
            "unknown(no-invocation-tag)",
            AuthStyleResolver.describe(Request.Builder().url(BASE_URL.toHttpUrl()).build()),
        )
    }
}
