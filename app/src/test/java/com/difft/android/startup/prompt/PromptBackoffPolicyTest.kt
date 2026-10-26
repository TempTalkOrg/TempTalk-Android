package com.difft.android.startup.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Time ladder 1/3/7/30 d then 30 d (#1355 D1 = A) and the clock-rollback clamp. */
class PromptBackoffPolicyTest {

    private val day = 86_400_000L
    private val now = 1_000 * day

    @Test
    fun `ladder is 1 3 7 30 days then stays at 30`() {
        assertEquals(0L, PromptBackoffPolicy.stepMillis(0))
        assertEquals(listOf(1L, 3L, 7L, 30L, 30L, 30L), (1..6).map { PromptBackoffPolicy.stepMillis(it) / day })
        assertEquals(30 * day, PromptBackoffPolicy.stepMillis(99))
    }

    @Test
    fun `consecutive declines walk the ladder from each decline time`() {
        var record = PromptRecord()
        var t = now
        val shownOnDays = mutableListOf(0L)
        repeat(5) {
            record = PromptBackoffPolicy.declined(record, t)
            t = record.snoozeUntil
            shownOnDays += (t - now) / day
        }
        // Shown on days 0, 1, 4, 11, 41, 71: five times in the first six weeks, then monthly.
        assertEquals(listOf(0L, 1L, 4L, 11L, 41L, 71L), shownOnDays)
        assertEquals(5, record.declines)
    }

    @Test
    fun `decline clears the showing and pending markers`() {
        val record = PromptBackoffPolicy.declined(PromptRecord(showingSince = 5, pendingActionAt = 6), now)
        assertEquals(0L, record.showingSince)
        assertEquals(0L, record.pendingActionAt)
        assertEquals(now + day, record.snoozeUntil)
    }

    @Test
    fun `clamp restarts a snooze longer than its step after the clock moved back`() {
        val rolledBack = PromptRecord(declines = 1, snoozeUntil = now + 40 * day)
        assertEquals(now + day, PromptBackoffPolicy.clamp(rolledBack, now).snoozeUntil)

        val noDecline = PromptRecord(declines = 0, snoozeUntil = now + 10 * day)
        assertEquals("a snooze without a decline never survives", now, PromptBackoffPolicy.clamp(noDecline, now).snoozeUntil)

        val normal = PromptRecord(declines = 4, snoozeUntil = now + 29 * day)
        assertEquals(normal, PromptBackoffPolicy.clamp(normal, now))
    }

    @Test
    fun `snoozed only before snoozeUntil`() {
        assertTrue(PromptBackoffPolicy.isSnoozed(PromptRecord(snoozeUntil = now + 1), now))
        assertFalse(PromptBackoffPolicy.isSnoozed(PromptRecord(snoozeUntil = now), now))
    }
}
