package com.difft.android.chat.contacts

import app.cash.turbine.test
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CountdownTickerTest {
    @Test fun `deadline is next local midnight including DST short and long days`() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(23 * HOUR, millisUntilNextMidnight(epoch("2026-03-08T05:00:00Z"), zone))
        assertEquals(25 * HOUR, millisUntilNextMidnight(epoch("2026-11-01T04:00:00Z"), zone))
        assertEquals(1L, millisUntilNextMidnight(epoch("2026-11-07T15:59:59.999Z"), ZoneId.of("Asia/Shanghai")))
    }

    @Test fun `flow waits for midnight then computes the next midnight instead of ticking immediately`() = runTest {
        val start = epoch("2026-11-07T23:59:59Z")
        midnightChanges({ start + currentTime }, { ZoneId.of("UTC") }).test {
            expectNoEvents()
            advanceTimeBy(999)
            runCurrent()
            expectNoEvents()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(Unit, awaitItem())
            advanceTimeBy(24 * HOUR)
            runCurrent()
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun epoch(instant: String) = Instant.parse(instant).toEpochMilli()
    private companion object { const val HOUR = 3_600_000L }
}
