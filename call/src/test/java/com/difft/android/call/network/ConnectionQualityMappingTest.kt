package com.difft.android.call.network

import io.livekit.android.room.participant.ConnectionQuality
import org.junit.Test
import kotlin.test.assertEquals












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
