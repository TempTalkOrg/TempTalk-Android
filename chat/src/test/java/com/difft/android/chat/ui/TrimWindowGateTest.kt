package com.difft.android.chat.ui

import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.ChatNormalPaginationController.Companion.TRIM_HIGH_WATER
import com.difft.android.chat.ChatNormalPaginationController.Companion.TRIM_SLACK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test













class TrimWindowGateTest {

    
    
    @Test
    fun `trims at the high-water mark when parked at the bottom and idle`() {
        assertTrue(
            shouldTrimWindow(
                windowSize = TRIM_HIGH_WATER,
                highWater = TRIM_HIGH_WATER,
                isLoadingPage = false,
            ) { true },
        )
        assertTrue(
            shouldTrimWindow(
                windowSize = TRIM_HIGH_WATER * 2,
                highWater = TRIM_HIGH_WATER,
                isLoadingPage = false,
            ) { true },
        )
    }

    
    
    @Test
    fun `never trims while the viewport is away from the bottom`() {
        assertFalse(
            shouldTrimWindow(
                windowSize = TRIM_HIGH_WATER,
                highWater = TRIM_HIGH_WATER,
                isLoadingPage = false,
            ) { false },
        )
        assertFalse(
            shouldTrimWindow(
                windowSize = TRIM_HIGH_WATER * 2,
                highWater = TRIM_HIGH_WATER,
                isLoadingPage = false,
            ) { false },
        )
    }

    
    @Test
    fun `never trims while a page load is in flight`() {
        assertFalse(
            shouldTrimWindow(
                windowSize = TRIM_HIGH_WATER,
                highWater = TRIM_HIGH_WATER,
                isLoadingPage = true,
            ) { true },
        )
    }

    
    
    @Test
    fun `below the high-water mark nothing is trimmed and the viewport is not read`() {
        var viewportReads = 0
        val verdict = shouldTrimWindow(
            windowSize = TRIM_HIGH_WATER - 1,
            highWater = TRIM_HIGH_WATER,
            isLoadingPage = false,
        ) { viewportReads++; true }

        assertFalse(verdict)
        assertEquals(0, viewportReads)
    }

    
    
    
    @Test
    fun `the size a trim produces cannot re-arm the gate`() {
        assertFalse(
            shouldTrimWindow(
                windowSize = MAX_MESSAGE_COUNT,
                highWater = TRIM_HIGH_WATER,
                isLoadingPage = false,
            ) { true },
        )
        assertTrue("hysteresis band must be non-empty", TRIM_SLACK > 0)
        assertEquals(MAX_MESSAGE_COUNT + TRIM_SLACK, TRIM_HIGH_WATER)
    }
}
