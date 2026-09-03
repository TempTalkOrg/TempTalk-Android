package com.difft.android.chat.ui

import com.difft.android.chat.ChatNormalPaginationController.Companion.MAX_MESSAGE_COUNT
import com.difft.android.chat.ChatNormalPaginationController.Companion.TRIM_HIGH_WATER
import com.difft.android.chat.ChatNormalPaginationController.Companion.TRIM_SLACK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases #56, #57 and #65 — the trim gate `ChatMessageListFragment.maybeTrimWindow` delegates to.
 *
 * #57 is the CRIT-2 regression: trimming drops rows off the OLDEST end, which is exactly where the
 * viewport is when the user has scrolled back into history, so `isAtBottom == false` must veto the
 * trim outright. #65 is termination: the size the trim itself produces must not re-arm the gate.
 *
 * The 4-host dimension (#58) does not appear here because there is nothing host-specific to
 * parameterise: all four hosts embed the same `ChatMessageListFragment` with no per-host branch, and
 * the only thing a half-screen Pop changes is what `isAtBottom` returns — an INPUT of this function.
 * Both values of that input are covered below.
 */
class TrimWindowGateTest {

    // #56 — the high-water mark is inclusive, and a viewport parked at the bottom is the one place a
    // trim is safe.
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

    // #57 — CRIT-2. However oversized the window is, a viewport that is not at the bottom is never
    // trimmed: those oldest rows are the ones on screen.
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

    // A page load in flight owns the window; let it finish and re-evaluate on its own commit.
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

    // Below the mark: hysteresis. Nothing is trimmed, and the viewport is not even read — this runs
    // on every list commit and `isAtBottom` costs three scroll computations.
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

    // #65 — termination. The trim re-slices to exactly MAX_MESSAGE_COUNT and re-emits, which runs
    // this gate again; the size it produced must be below the mark or the trim would re-arm itself
    // forever.
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
