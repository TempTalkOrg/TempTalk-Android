package com.difft.android.call.network

import io.livekit.android.room.participant.ConnectionQuality
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [toNetworkQualityLevel] — the single SDK boundary of the weak-network feature.
 *
 * These pin the two non-obvious mapping decisions:
 *  - `UNKNOWN` maps to EXCELLENT (render nothing) rather than to a fault tier, so no badge flashes
 *    on join before the first stats batch lands;
 *  - `POOR` and `LOST` collapse into one BAD tier (the design has a single weak-network visual).
 *
 * Pure JVM tier: `ConnectionQuality`'s static initialiser touches only itself and `kotlin.enums`,
 * so no Robolectric runtime is needed.
 */
class ConnectionQualityMappingTest {

    @Test
    fun `excellent and unknown both map to excellent`() {
        assertEquals(NetworkQualityLevel.EXCELLENT, ConnectionQuality.EXCELLENT.toNetworkQualityLevel())
        assertEquals(NetworkQualityLevel.EXCELLENT, ConnectionQuality.UNKNOWN.toNetworkQualityLevel())
    }

    @Test
    fun `good maps to good`() {
        assertEquals(NetworkQualityLevel.GOOD, ConnectionQuality.GOOD.toNetworkQualityLevel())
    }

    @Test
    fun `poor and lost both map to bad`() {
        assertEquals(NetworkQualityLevel.BAD, ConnectionQuality.POOR.toNetworkQualityLevel())
        assertEquals(NetworkQualityLevel.BAD, ConnectionQuality.LOST.toNetworkQualityLevel())
    }
}
