package com.difft.android.network.proxy

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure derivation function [computeTunnelHosts]
 * (`ProxyTunnelHostDerivation.kt`). Pure JVM — no Android dependencies, no
 * Robolectric runner required.
 *
 * The whitelist is the union of the EMBEDDED chat hosts and the EMBEDDED
 * call-service domains; there is no live/dynamic source and no hardcoded
 * baseline. Each test pins one observable property of the derivation contract.
 */
class ComputeTunnelHostsTest {

    @Test
    fun `both sources populated produces union with dedup`() {
        val chatHosts = listOf("chat.temptalk.net", "chat.chative.im")
        val callDomains = listOf("call-primary.ablivekit.org", "call-fb.ablivekit.org")

        val result = computeTunnelHosts(chatHosts, callDomains)

        assertEquals(4, result.size)
        assertTrue("chat.temptalk.net" in result)
        assertTrue("chat.chative.im" in result)
        assertTrue("call-primary.ablivekit.org" in result)
        assertTrue("call-fb.ablivekit.org" in result)
    }

    @Test
    fun `empty chat hosts plus non-empty call yields call entry only`() {
        val result = computeTunnelHosts(emptyList(), listOf("call.ablivekit.org"))

        assertEquals(setOf("call.ablivekit.org"), result)
    }

    @Test
    fun `non-empty chat hosts plus empty call yields chat entry only`() {
        val result = computeTunnelHosts(listOf("chat.temptalk.net"), emptyList())

        assertEquals(setOf("chat.temptalk.net"), result)
    }

    @Test
    fun `both sources empty yields empty set`() {
        val result = computeTunnelHosts(emptyList(), emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `normalization lowercases trims trimEnds dot and drops blank`() {
        val chatHosts = listOf("  Chat.Temptalk.NET  ", "chat.chative.im.", "", "   ")
        val callDomains = emptyList<String>()

        val result = computeTunnelHosts(chatHosts, callDomains)

        assertTrue("chat.temptalk.net" in result)
        assertTrue("chat.chative.im" in result)
        // blanks dropped, trailing dot stripped, case normalized → exactly 2 entries.
        assertEquals(2, result.size)
    }

    @Test
    fun `dedup across both sources reduces to single member`() {
        val result = computeTunnelHosts(listOf("chat.chative.im"), listOf("chat.chative.im"))

        assertEquals(setOf("chat.chative.im"), result)
        assertEquals(1, result.size)
    }

    @Test
    fun `insertion order is chat hosts then call domains`() {
        val result = computeTunnelHosts(
            listOf("chat.temptalk.net"),
            listOf("call.ablivekit.org"),
        )

        assertEquals(listOf("chat.temptalk.net", "call.ablivekit.org"), result.toList())
    }
}
