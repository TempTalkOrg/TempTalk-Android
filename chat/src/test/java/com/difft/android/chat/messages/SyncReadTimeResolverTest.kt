package com.difft.android.chat.messages

import org.junit.Assert.assertEquals
import org.junit.Test





class SyncReadTimeResolverTest {

    
    private val base = 1_700_000_000_000L

    
    @Test
    fun `normal readAt below envelope is used`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = base + 2_000L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 2_000L, result)
    }

    
    @Test
    fun `zero readAt falls back to message timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = 0L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 1_000L, result)
    }

    
    @Test
    fun `negative readAt falls back to message timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = -5L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 1_000L, result)
    }

    
    @Test
    fun `sub-plausible readAt below floor falls back to message timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = 1_700_000_000L, 
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 1_000L, result)
    }

    
    @Test
    fun `future readAt is clamped by envelope server timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = base + 9_999_999L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 3_000L, result)
    }

    
    @Test
    fun `readAt equal to envelope is unchanged`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = base + 3_000L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 3_000L, result)
    }

    
    
    @Test
    fun `valid readAt with no server bound falls back to message timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = base + 2_000L,
            envelopeServerTimestamp = 0L,
            fallback = base + 1_000L
        )
        assertEquals(base + 1_000L, result)
    }
}
