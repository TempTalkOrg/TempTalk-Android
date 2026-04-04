package com.difft.android.network.speedtest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HostSpeedResultTest {

    @Test
    fun `available result has finite latency`() {
        val result = HostSpeedResult(host = "chat.temptalk.net", latencyMs = 150, isAvailable = true)

        assertEquals("chat.temptalk.net", result.host)
        assertEquals(150, result.latencyMs)
        assertTrue(result.isAvailable)
    }

    @Test
    fun `unavailable result uses Long MAX_VALUE for latency`() {
        val result = HostSpeedResult(host = "down.host", latencyMs = Long.MAX_VALUE, isAvailable = false)

        assertEquals(Long.MAX_VALUE, result.latencyMs)
        assertFalse(result.isAvailable)
    }

    @Test
    fun `unavailable hosts sort after available hosts by latency`() {
        val available = HostSpeedResult("fast.host", 50, true)
        val unavailable = HostSpeedResult("down.host", Long.MAX_VALUE, false)

        val sorted = listOf(unavailable, available).sortedBy { it.latencyMs }

        assertEquals("fast.host", sorted.first().host)
        assertEquals("down.host", sorted.last().host)
    }

    @Test
    fun `available hosts sort by latency ascending`() {
        val slow = HostSpeedResult("slow.host", 500, true)
        val fast = HostSpeedResult("fast.host", 50, true)
        val medium = HostSpeedResult("medium.host", 200, true)

        val sorted = listOf(slow, fast, medium).sortedBy { it.latencyMs }

        assertEquals("fast.host", sorted[0].host)
        assertEquals("medium.host", sorted[1].host)
        assertEquals("slow.host", sorted[2].host)
    }

    @Test
    fun `data class equality works correctly`() {
        val result1 = HostSpeedResult("host.a", 100, true)
        val result2 = HostSpeedResult("host.a", 100, true)
        val result3 = HostSpeedResult("host.b", 100, true)

        assertEquals(result1, result2)
        assertNotEquals(result1, result3)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = HostSpeedResult("host.a", 100, true)
        val copied = original.copy(isAvailable = false, latencyMs = Long.MAX_VALUE)

        assertEquals("host.a", copied.host)
        assertEquals(Long.MAX_VALUE, copied.latencyMs)
        assertFalse(copied.isAvailable)
    }
}
