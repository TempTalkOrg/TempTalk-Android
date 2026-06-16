package com.difft.android.network.proxy

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure derivation function [computeTunnelHosts]
 * (`ProxyTunnelHostDerivation.kt`). Pure JVM — no Android dependencies, no
 * Robolectric runner required.
 *
 * Each test pins one observable property of the derivation contract documented
 * in design §3.4 / §10.
 */
class ComputeTunnelHostsTest {

    /** Mirrors `ProxyConfigProvider.HARDCODED_BASELINE` (kept in lockstep). */
    private val baseline: Set<String> = setOf(
        "chative.im",
        "temptalk.net",
        "ablivekit.org",
        "chative.online",
        "chative.ninja",
    )

    @Test
    fun `T1 derivation - both sources populated produces union with dedup`() {
        // Arrange
        val global = listOf("api.chative.im", "wss.temptalk.net")
        val call = listOf("call-primary.ablivekit.org", "call-fb.ablivekit.org")

        // Act
        val result = computeTunnelHosts(global, call, baseline)

        // Assert: 5 baseline entries (none collide with input — input entries are
        // distinct FQDNs from the baseline parent-domain entries) + 4 inputs = 9.
        assertEquals(9, result.size)
        assertTrue(result.containsAll(baseline))
        assertTrue("api.chative.im" in result)
        assertTrue("wss.temptalk.net" in result)
        assertTrue("call-primary.ablivekit.org" in result)
        assertTrue("call-fb.ablivekit.org" in result)
    }

    @Test
    fun `T2 derivation - empty global plus non-empty call yields baseline plus call entry`() {
        // Arrange
        val global = emptyList<String>()
        val call = listOf("call.ablivekit.org")

        // Act
        val result = computeTunnelHosts(global, call, baseline)

        // Assert
        assertEquals(6, result.size)
        assertTrue(result.containsAll(baseline))
        assertTrue("call.ablivekit.org" in result)
    }

    @Test
    fun `T3 derivation - non-empty global plus empty call yields baseline plus global entry`() {
        // Arrange
        val global = listOf("self.example.com")
        val call = emptyList<String>()

        // Act
        val result = computeTunnelHosts(global, call, baseline)

        // Assert
        assertEquals(6, result.size)
        assertTrue(result.containsAll(baseline))
        assertTrue("self.example.com" in result)
    }

    @Test
    fun `T4 derivation - both sources empty yields baseline only`() {
        // Act
        val result = computeTunnelHosts(emptyList(), emptyList(), baseline)

        // Assert: exactly baseline.
        assertEquals(baseline, result)
    }

    @Test
    fun `T5 extractGlobalSelfCertHosts - filters certType self across both list shapes`() {
        // T5 covers the extractor behavior; the test for the extractor lives in
        // ProxyConfigProviderTunnelHostsTest because it requires the provider's
        // visibility. Here we cover the equivalent pure-function path: confirm
        // that whatever the extractor returns is unioned as-is by
        // computeTunnelHosts (i.e. computeTunnelHosts does NOT re-filter by
        // certType — that's the extractor's job, not the derivation's).

        // Arrange — simulate the extractor having already produced ["a.com", "d.com"]
        // from a NewGlobalConfig with mixed self/authority entries.
        val global = listOf("a.com", "d.com")

        // Act
        val result = computeTunnelHosts(global, emptyList(), baseline)

        // Assert: both extracted entries flow through, baseline preserved.
        assertTrue("a.com" in result)
        assertTrue("d.com" in result)
        assertTrue(result.containsAll(baseline))
        assertEquals(baseline.size + 2, result.size)
    }

    @Test
    fun `T6 derivation - normalization lowercases trims trimEnds dot and drops blank`() {
        // Arrange — mixed-case, padded, trailing dot, blank entries.
        val global = listOf("  Api.Chative.IM  ", "wss.temptalk.net.", "", "   ")
        val call = emptyList<String>()

        // Act
        val result = computeTunnelHosts(global, call, baseline)

        // Assert: blanks dropped, trailing dot stripped, case normalized.
        assertTrue("api.chative.im" in result)
        assertTrue("wss.temptalk.net" in result)
        // 5 baseline + "api.chative.im" + "wss.temptalk.net" = 7 (api.chative.im
        // is distinct from baseline "chative.im" — per-FQDN match semantics).
        assertEquals(7, result.size)
    }

    @Test
    fun `T7 derivation - dedup across sources reduces to single member`() {
        // Arrange — same entry appears in all three sources.
        val global = listOf("chative.im")
        val call = listOf("chative.im")
        val singletonBaseline = setOf("chative.im")

        // Act
        val result = computeTunnelHosts(global, call, singletonBaseline)

        // Assert: Set semantics dedup the three contributions to one entry.
        assertEquals(setOf("chative.im"), result)
        assertEquals(1, result.size)
    }
}
