package com.difft.android.startup.prompt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundConnectionHistoryTest : StartupPromptCoordinatorTestBase() {

    private val bg = PromptId.BACKGROUND_CONNECTION

    private fun triggers(vararg t: BackgroundTrigger) {
        answers[bg] = ProviderAnswer(Eligibility.ELIGIBLE, triggers = t.toSet())
    }

    @Test
    fun `a decline's history survives not eligible and FCM unavailable follows only the ladder`() = runTest {
        triggers(BackgroundTrigger.LATE_DELIVERY)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onDeclined(ticket)
        assertEquals(now + 30 * day, record(bg)?.floorUntil)

        
        answers[bg] = ProviderAnswer.NOT_ELIGIBLE
        changes.emit(Unit)
        runCurrent()
        assertNotNull("history survives a confirmed NOT_ELIGIBLE", record(bg))
        assertEquals(1, record(bg)?.declines)
        assertEquals(now + 30 * day, record(bg)?.floorUntil)
        endSession()

        
        triggers(BackgroundTrigger.FCM_UNAVAILABLE)
        now += 2 * day
        startSession(c)
        assertEquals(BackgroundTrigger.FCM_UNAVAILABLE, c.current.value?.backgroundTrigger)
    }

    @Test
    fun `late-delivery evidence waits a week after a decline while FCM unavailable only follows the ladder`() = runTest {
        triggers(BackgroundTrigger.LATE_DELIVERY)
        val c = coordinator()
        startSession(c)
        val first = c.current.value!!
        c.onShown(first)
        c.onDeclined(first)
        endSession()

        now += 2 * day
        startSession(c)
        assertNull("inside the late-delivery week", c.current.value)
        endSession()

        triggers(BackgroundTrigger.LATE_DELIVERY, BackgroundTrigger.FCM_UNAVAILABLE)
        startSession(c)
        assertEquals(BackgroundTrigger.FCM_UNAVAILABLE, c.current.value?.backgroundTrigger)
        val second = c.current.value!!
        c.onShown(second)
        c.onDeclined(second)
        endSession()

        
        triggers(BackgroundTrigger.LATE_DELIVERY)
        now += 6 * day
        startSession(c)
        assertNull("day 8: still inside the late-delivery week", c.current.value)
        endSession()

        now += 2 * day
        startSession(c)
        assertEquals("day 10: late evidence prompts again", BackgroundTrigger.LATE_DELIVERY, c.current.value?.backgroundTrigger)
    }

    @Test
    fun `a record without any history is still dropped when the problem is gone`() = runTest {
        store.update { mapOf(bg to PromptRecord(shownCount = 1, lastShownAt = now)) }
        answers[bg] = ProviderAnswer.NOT_ELIGIBLE
        val c = coordinator()
        startSession(c)
        assertNull(record(bg))
    }

    @Test
    fun `an action that ends in a fix settles the record without a decline`() = runTest {
        triggers(BackgroundTrigger.FCM_UNAVAILABLE)
        store.update { mapOf(bg to PromptRecord(declines = 1, floorUntil = now - 1)) }
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        assertTrue(c.onActionTaken(ticket))
        assertTrue(record(bg)!!.pendingActionAt != 0L)

        answers[bg] = ProviderAnswer.NOT_ELIGIBLE
        c.setHostState(true, false)
        runCurrent()
        val settled = record(bg)!!
        assertEquals(0L, settled.pendingActionAt)
        assertEquals(0L, settled.showingSince)
        assertEquals(1, settled.declines)
    }

    @Test
    fun `an action that returns unfixed counts as a decline and starts the floor`() = runTest {
        triggers(BackgroundTrigger.LATE_DELIVERY)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onActionTaken(ticket)
        val actionAt = now
        endSession()

        now += 40 * 60_000
        startSession(c)
        val after = record(bg)!!
        assertEquals(1, after.declines)
        assertEquals(actionAt + 30 * day, after.floorUntil)
        assertEquals(0L, after.pendingActionAt)
        assertNull(c.current.value)
    }

    @Test
    fun `an explicit switch-off floors every trigger, evidence included`() = runTest {
        store.recordBackgroundConnectionSwitch(on = false, now = now)
        assertTrue(record(bg)!!.switchedOff)
        assertEquals(now + 30 * day, record(bg)!!.floorUntil)
        assertEquals("a switch-off is not a decline", 0, record(bg)!!.declines)

        triggers(BackgroundTrigger.LATE_DELIVERY, BackgroundTrigger.FCM_UNAVAILABLE)
        val c = coordinator()
        startSession(c)
        assertNull("inside the switch-off floor", c.current.value)
        endSession()

        now += 31 * day
        startSession(c)
        assertEquals(BackgroundTrigger.FCM_UNAVAILABLE, c.current.value?.backgroundTrigger)
    }

    @Test
    fun `switching on clears the switched-off flag and the floor keeps running`() = runTest {
        store.recordBackgroundConnectionSwitch(on = false, now = now)
        store.recordBackgroundConnectionSwitch(on = true, now = now + 1)
        assertFalse(record(bg)!!.switchedOff)
        assertEquals(now + 30 * day, record(bg)!!.floorUntil)

        
        val clean = StartupPromptStore(com.difft.android.test.fakes.RecordingPreferencesDataStore(), com.google.gson.Gson())
        clean.recordBackgroundConnectionSwitch(on = true, now = now)
        assertTrue(clean.read().isEmpty())
    }

    @Test
    fun `the copy trigger follows FCM unavailable before late evidence`() = runTest {
        triggers(BackgroundTrigger.LATE_DELIVERY, BackgroundTrigger.FCM_UNAVAILABLE)
        val c = coordinator()
        startSession(c)
        assertEquals(BackgroundTrigger.FCM_UNAVAILABLE, c.current.value?.backgroundTrigger)
    }

    @Test
    fun `a decline after the switch-off floor follows the normal ladder again`() = runTest {
        store.recordBackgroundConnectionSwitch(on = false, now = now)
        triggers(BackgroundTrigger.FCM_UNAVAILABLE)
        val c = coordinator()
        now += 31 * day
        startSession(c)
        val ticket = c.current.value!!
        assertEquals(BackgroundTrigger.FCM_UNAVAILABLE, ticket.backgroundTrigger)
        c.onShown(ticket)
        c.onDeclined(ticket)
        assertFalse("a decline ends the switch-off state", record(bg)!!.switchedOff)
        endSession()

        now += 2 * day
        startSession(c)
        assertEquals("ladder, not another 30-day floor", BackgroundTrigger.FCM_UNAVAILABLE, c.current.value?.backgroundTrigger)
    }

    @Test
    fun `the floor is clamped when the clock moved back`() {
        val r = PromptRecord(declines = 1, snoozeUntil = 0, floorUntil = 100 * day)
        val clamped = PromptBackoffPolicy.clamp(r, 10 * day)
        assertEquals(10 * day + 30 * day, clamped.floorUntil)
    }
}
