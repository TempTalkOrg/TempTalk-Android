package util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone





class TimeFormatterIsSameDayTest {

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(null)
    }

    @Test
    fun `the same instant is the same day`() {
        withDefaultZone(SHANGHAI) { zone ->
            val ts = instant(zone, 2026, 5, 8, 14, 30)
            assertTrue(TimeFormatter.isSameDay(ts, ts))
            assertReferenceAgrees(ts, ts)
        }
    }

    @Test
    fun `different hours of one local day are the same day`() {
        listOf(SHANGHAI, NEW_YORK, KATHMANDU).forEach { zoneId ->
            withDefaultZone(zoneId) { zone ->
                val dayStart = instant(zone, 2026, 5, 8, 0, 0, 0, 0)
                val dayEnd = instant(zone, 2026, 5, 8, 23, 59, 59, 999)

                assertTrue(zoneId, TimeFormatter.isSameDay(dayStart, dayEnd))
                assertReferenceAgrees(dayStart, dayEnd)
            }
        }
    }

    @Test
    fun `adjacent days across local midnight are different days`() {
        listOf(SHANGHAI, NEW_YORK, KATHMANDU).forEach { zoneId ->
            withDefaultZone(zoneId) { zone ->
                val beforeMidnight = instant(zone, 2026, 5, 8, 23, 59, 59, 999)
                val afterMidnight = instant(zone, 2026, 5, 9, 0, 0, 0, 0)

                assertFalse(zoneId, TimeFormatter.isSameDay(beforeMidnight, afterMidnight))
                assertReferenceAgrees(beforeMidnight, afterMidnight)
            }
        }
    }

    




    @Test
    fun `a DST spring-forward day stays one day`() {
        withDefaultZone(NEW_YORK) { zone ->
            val beforeGap = instant(zone, 2026, 3, 8, 1, 30)
            val afterGap = instant(zone, 2026, 3, 8, 3, 30)
            val nextDay = instant(zone, 2026, 3, 9, 0, 30)
            val previousDay = instant(zone, 2026, 3, 7, 23, 30)

            assertTrue("across the gap", TimeFormatter.isSameDay(beforeGap, afterGap))
            assertFalse("into the next day", TimeFormatter.isSameDay(afterGap, nextDay))
            assertFalse("out of the previous day", TimeFormatter.isSameDay(previousDay, beforeGap))
            assertReferenceAgrees(beforeGap, afterGap)
            assertReferenceAgrees(afterGap, nextDay)
            assertReferenceAgrees(previousDay, beforeGap)
        }
    }

    



    @Test
    fun `a DST fall-back day stays one day including the repeated hour`() {
        val firstOneThirty = utcInstant(2026, 11, 1, 5, 30)
        val secondOneThirty = utcInstant(2026, 11, 1, 6, 30)
        val dayStart = utcInstant(2026, 11, 1, 4, 0)
        val dayEnd = utcInstant(2026, 11, 2, 4, 59, 59, 999)
        val nextDayStart = utcInstant(2026, 11, 2, 5, 0)

        withDefaultZone(NEW_YORK) {
            assertTrue("repeated hour", TimeFormatter.isSameDay(firstOneThirty, secondOneThirty))
            assertTrue("full 25-hour day", TimeFormatter.isSameDay(dayStart, dayEnd))
            assertFalse("next day", TimeFormatter.isSameDay(dayEnd, nextDayStart))
            assertReferenceAgrees(firstOneThirty, secondOneThirty)
            assertReferenceAgrees(dayStart, dayEnd)
            assertReferenceAgrees(dayEnd, nextDayStart)
        }
    }

    @Test
    fun `a year boundary is a day boundary and equal day-of-year across years is not`() {
        withDefaultZone(SHANGHAI) { zone ->
            val lastMomentOf2025 = instant(zone, 2025, 12, 31, 23, 59, 59, 999)
            val firstMomentOf2026 = instant(zone, 2026, 1, 1, 0, 0, 0, 0)
            val juneIn2025 = instant(zone, 2025, 6, 1, 12, 0)
            val juneIn2026 = instant(zone, 2026, 6, 1, 12, 0)

            assertFalse("year boundary", TimeFormatter.isSameDay(lastMomentOf2025, firstMomentOf2026))
            assertFalse("same date, different year", TimeFormatter.isSameDay(juneIn2025, juneIn2026))
            assertReferenceAgrees(lastMomentOf2025, firstMomentOf2026)
            assertReferenceAgrees(juneIn2025, juneIn2026)
        }
    }

    
    @Test
    fun `negative timestamps keep their own days`() {
        withDefaultZone(UTC) { zone ->
            val morningBeforeEpoch = instant(zone, 1969, 12, 31, 10, 0)
            val eveningBeforeEpoch = instant(zone, 1969, 12, 31, 23, 0)
            val lastMillisBeforeEpoch = -1L
            val epoch = 0L

            assertTrue(TimeFormatter.isSameDay(morningBeforeEpoch, eveningBeforeEpoch))
            assertFalse(TimeFormatter.isSameDay(lastMillisBeforeEpoch, epoch))
            assertTrue(TimeFormatter.isSameDay(lastMillisBeforeEpoch, eveningBeforeEpoch))
            assertReferenceAgrees(morningBeforeEpoch, eveningBeforeEpoch)
            assertReferenceAgrees(lastMillisBeforeEpoch, epoch)
            assertReferenceAgrees(lastMillisBeforeEpoch, eveningBeforeEpoch)
        }
    }

    



    @Test
    fun `matches the Calendar implementation across a timestamp sweep`() {
        var comparisons = 0
        var sameDayResults = 0

        listOf(UTC, NEW_YORK, KATHMANDU, LORD_HOWE).forEach { zoneId ->
            withDefaultZone(zoneId) {
                SWEEP_STARTS.forEach { start ->
                    var offsetMs = 0L
                    while (offsetMs < SWEEP_SPAN_MS) {
                        val first = start + offsetMs
                        SWEEP_DELTAS.forEach { delta ->
                            val second = first + delta
                            val actual = TimeFormatter.isSameDay(first, second)
                            assertEquals(
                                "$zoneId first=$first second=$second",
                                calendarIsSameDay(first, second),
                                actual,
                            )
                            comparisons++
                            if (actual) sameDayResults++
                        }
                        offsetMs += SWEEP_STEP_MS
                    }
                }
            }
        }

        
        assertTrue("sweep ran: $comparisons", comparisons > 10_000)
        assertTrue("sweep produced same-day hits", sameDayResults > comparisons / 10)
        assertTrue("sweep produced different-day hits", sameDayResults < comparisons * 9 / 10)
    }

    private fun assertReferenceAgrees(timestamp1: Long, timestamp2: Long) {
        assertEquals(
            "reference disagrees for $timestamp1 / $timestamp2 in ${TimeZone.getDefault().id}",
            calendarIsSameDay(timestamp1, timestamp2),
            TimeFormatter.isSameDay(timestamp1, timestamp2),
        )
    }

    
    private fun calendarIsSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        val calendar1 = Calendar.getInstance()
        val calendar2 = Calendar.getInstance()

        calendar1.timeInMillis = timestamp1
        calendar2.timeInMillis = timestamp2

        return calendar1.get(Calendar.YEAR) == calendar2.get(Calendar.YEAR) &&
            calendar1.get(Calendar.DAY_OF_YEAR) == calendar2.get(Calendar.DAY_OF_YEAR)
    }

    private fun <T> withDefaultZone(zoneId: String, block: (TimeZone) -> T): T {
        val previous = TimeZone.getDefault()
        val zone = TimeZone.getTimeZone(zoneId)
        TimeZone.setDefault(zone)
        return try {
            block(zone)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun instant(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
        millis: Int = 0,
    ): Long {
        val calendar = GregorianCalendar(zone)
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(Calendar.MILLISECOND, millis)
        return calendar.timeInMillis
    }

    private fun utcInstant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
        millis: Int = 0,
    ): Long = instant(TimeZone.getTimeZone(UTC), year, month, day, hour, minute, second, millis)

    private companion object {
        const val UTC = "UTC"
        const val SHANGHAI = "Asia/Shanghai"
        const val NEW_YORK = "America/New_York"

        
        const val KATHMANDU = "Asia/Kathmandu"

        
        const val LORD_HOWE = "Australia/Lord_Howe"

        
        val SWEEP_STARTS = listOf(
            1_772_841_600_000L, 
            1_793_491_200_000L, 
            -86_400_000L, 
        )
        const val SWEEP_SPAN_MS = 3 * 86_400_000L
        const val SWEEP_STEP_MS = 17 * 60 * 1000L
        val SWEEP_DELTAS = listOf(
            0L,
            1L,
            -1L,
            3_600_000L,
            43_200_000L,
            86_400_000L,
            -86_400_000L,
            86_400_001L,
        )
    }
}
