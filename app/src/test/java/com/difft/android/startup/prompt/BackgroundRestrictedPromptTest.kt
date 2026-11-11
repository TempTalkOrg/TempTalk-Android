package com.difft.android.startup.prompt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundRestrictedPromptTest : StartupPromptCoordinatorTestBase() {

    private val slot = PromptId.BACKGROUND_RESTRICTED

    private fun eligible() {
        answers[slot] = ProviderAnswer(Eligibility.ELIGIBLE)
    }

    @Test
    fun `answer needs the running service, keep-alive, notifications and two problem days`() {
        assertEquals(Eligibility.ELIGIBLE, backgroundRestrictedAnswer(true, true, true, 2).eligibility)
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, backgroundRestrictedAnswer(false, true, true, 7))
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, backgroundRestrictedAnswer(true, false, true, 7))
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, backgroundRestrictedAnswer(true, true, false, 7))
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, backgroundRestrictedAnswer(true, true, true, 1))
    }

    @Test
    fun `only problem days after keep-alive was switched on count`() {
        
        assertEquals(0, restrictedProblemDays(setOf(1L, 2L), emptySet(), keepAliveOnDay = 2L))
        assertEquals(2, restrictedProblemDays(setOf(1L, 3L), setOf(3L, 4L), keepAliveOnDay = 2L))
    }

    @Test
    fun `a decline waits 30 days and the second decline stops the slot for good`() = runTest {
        eligible()
        val c = coordinator()
        startSession(c)
        val first = c.current.value!!
        assertEquals(slot, first.id)
        c.onShown(first)
        c.onDeclined(first)
        assertEquals(now + 30 * day, record(slot)?.snoozeUntil)
        endSession()

        now += 29 * day
        startSession(c)
        assertNull("inside the 30-day interval", c.current.value)
        endSession()

        now += 2 * day
        startSession(c)
        val second = c.current.value!!
        c.onShown(second)
        c.onDeclined(second)
        endSession()

        now += 365 * day
        startSession(c)
        assertNull("two declines stop the slot", c.current.value)
    }

    @Test
    fun `a settings trip that returns unfixed is no decline and waits 30 days`() = runTest {
        eligible()
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        assertTrue(c.onActionTaken(ticket))
        val tripAt = now
        endSession()

        now += 40 * 60_000
        startSession(c)
        val after = record(slot)!!
        assertEquals(0, after.declines)
        assertEquals(0L, after.pendingActionAt)
        assertEquals(tripAt + 30 * day, after.snoozeUntil)
        assertNull(c.current.value)
        endSession()

        now = tripAt + 30 * day + 1
        startSession(c)
        assertEquals(slot, c.current.value?.id)
    }

    @Test
    fun `an aborted settings trip leaves no interval`() = runTest {
        eligible()
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        assertTrue(c.onActionTaken(ticket))
        c.onActionAborted(ticket)

        assertEquals(0L, record(slot)?.snoozeUntil)
        assertEquals(0L, record(slot)?.pendingActionAt)
    }

    @Test
    fun `a settings trip that fixed the problem still keeps its interval`() = runTest {
        eligible()
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onActionTaken(ticket)
        val tripAt = now
        endSession()

        answers[slot] = ProviderAnswer.NOT_ELIGIBLE
        now += 40 * 60_000
        startSession(c)
        assertEquals(tripAt + 30 * day, record(slot)?.snoozeUntil)
        endSession()

        eligible()
        now = tripAt + 10 * day
        startSession(c)
        assertNull("the problem returning inside the interval does not prompt", c.current.value)
    }

    @Test
    fun `history survives the problem going away`() = runTest {
        eligible()
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onDeclined(ticket)

        answers[slot] = ProviderAnswer.NOT_ELIGIBLE
        changes.emit(Unit)
        runCurrent()
        assertNotNull(record(slot))
        assertEquals(1, record(slot)?.declines)
        assertEquals(now + 30 * day, record(slot)?.snoozeUntil)
    }

    @Test
    fun `the interval is clamped to 30 days when the clock moved back`() {
        val record = PromptRecord(snoozeUntil = now + 90 * day)
        assertEquals(now + 30 * day, PromptBackoffPolicy.clamp(record, now, slot).snoozeUntil)
        assertEquals("other slots keep the ladder clamp", now, PromptBackoffPolicy.clamp(record, now).snoozeUntil)
    }
}
