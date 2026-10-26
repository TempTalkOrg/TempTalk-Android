package com.difft.android.startup.prompt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notifications slot's system-permission flow, offer identity, failed writes and stale answers
 * (#1355), on the shared coordinator harness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupPromptCoordinatorFlowTest : StartupPromptCoordinatorTestBase() {

    @Test
    fun `upgrader with a permanent denial gets the app dialog in the same session, then directly`() = runTest {
        // No state is carried over from older versions, while the permission was denied for good.
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF, requestable = true)
        val c = coordinator()
        startSession(c)
        val system = c.current.value!!
        assertTrue(system.viaSystemRequest)
        c.onShown(system)
        assertTrue(c.onActionTaken(system, rationaleBefore = false))
        // The system answers without a dialog.
        c.onNotificationRequestResult(granted = false, rationaleAfter = false)
        val dialog = c.current.value!!
        assertEquals(PromptId.NOTIFICATIONS, dialog.id)
        assertFalse(dialog.viaSystemRequest)
        assertEquals(2, record(PromptId.NOTIFICATIONS)?.systemDenials)
        c.onShown(dialog)
        c.onDeclined(dialog)
        assertEquals(1, record(PromptId.NOTIFICATIONS)?.declines)

        endSession()
        now += 3 * day
        startSession(c)
        assertFalse("system route exhausted: the app dialog directly", c.current.value!!.viaSystemRequest)
        c.onDeclined(c.current.value!!)

        // Permission reset to "ask every time": the system can show its dialog again. The host
        // pauses before the session ends and reports the fresh rationale when it is back.
        c.setHostState(false, false)
        runCurrent()
        endSession()
        now += 7 * day
        startSession(c, rationale = true)
        assertTrue(c.current.value!!.viaSystemRequest)
    }

    @Test
    fun `rationale before the request survives process death in the system dialog`() = runTest {
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF, requestable = true)
        val c = coordinator()
        startSession(c, rationale = true)
        val system = c.current.value!!
        c.onShown(system)
        c.onActionTaken(system, rationaleBefore = true)

        // A fresh coordinator (new process) receives the result: a second, visible denial.
        val revived = coordinator()
        revived.onNotificationRequestResult(granted = false, rationaleAfter = false)
        val after = record(PromptId.NOTIFICATIONS)!!
        assertEquals(1, after.systemDenials)
        assertEquals(1, after.declines)
        assertEquals(0L, after.pendingActionAt)
        assertNull("a visible denial does not open the app dialog", revived.current.value)
    }

    @Test
    fun `a declined offer does not suppress a new offer after process death`() = runTest {
        answers[PromptId.OPTIONAL_UPDATE] = ProviderAnswer(Eligibility.ELIGIBLE, offerKey = "H1")
        val c = coordinator()
        startSession(c)
        val h1 = c.current.value!!
        assertEquals("H1", h1.offerKey)
        c.onShown(h1)
        c.onDeclined(h1)
        assertEquals("H1", record(PromptId.OPTIONAL_UPDATE)?.offerKey)
        endSession()

        answers[PromptId.OPTIONAL_UPDATE] = ProviderAnswer(Eligibility.ELIGIBLE, offerKey = "H2")
        val revived = coordinator()
        startSession(revived)
        assertEquals(PromptId.OPTIONAL_UPDATE, revived.current.value?.id)
        assertEquals("H2", revived.current.value?.offerKey)
        assertEquals(0, record(PromptId.OPTIONAL_UPDATE)?.declines)
    }

    @Test
    fun `a pending action still unknown after the own-trip window counts as one decline`() = runTest {
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onActionTaken(ticket)
        endSession()

        answers[PromptId.NOTIFICATIONS] = ProviderAnswer.UNKNOWN
        now += 10 * 60_000
        startSession(c)
        assertTrue("inside the window it waits", record(PromptId.NOTIFICATIONS)!!.pendingActionAt != 0L)

        endSession()
        now += 25 * 60_000
        startSession(c)
        val expired = record(PromptId.NOTIFICATIONS)!!
        assertEquals(0L, expired.pendingActionAt)
        assertEquals(1, expired.declines)
    }

    @Test
    fun `a silent denial under an app-wide gate publishes no dialog until the gate clears`() = runTest {
        store.update { mapOf(PromptId.NOTIFICATIONS to PromptRecord(systemDenials = 2)) }
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF, requestable = true)
        val c = coordinator()
        startSession(c, rationale = true)
        val system = c.current.value!!
        assertTrue(system.viaSystemRequest)
        c.onShown(system)
        c.onActionTaken(system, rationaleBefore = false)

        // The request is outstanding when a 451 raises the inactive-device gate.
        gates.value = setOf(GateId.INACTIVE_DEVICE)
        runCurrent()
        c.onNotificationRequestResult(granted = false, rationaleAfter = false)
        assertNull("no dialog over the gate", c.current.value)
        c.setHostState(true, false)
        runCurrent()
        assertNull(c.current.value)
        val record = record(PromptId.NOTIFICATIONS)!!
        assertEquals(0L, record.pendingActionAt)
        assertEquals(0, record.declines)

        gates.value = emptySet()
        runCurrent()
        c.setHostState(false, false)
        runCurrent()
        endSession()
        startSession(c)
        val dialog = c.current.value!!
        assertEquals(PromptId.NOTIFICATIONS, dialog.id)
        assertFalse(dialog.viaSystemRequest)
    }

    @Test
    fun `a preempted app dialog shows again in the same session once the gate clears`() = runTest {
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF, requestable = true)
        val c = coordinator()
        startSession(c)
        val system = c.current.value!!
        c.onShown(system)
        c.onActionTaken(system, rationaleBefore = false)
        c.onNotificationRequestResult(granted = false, rationaleAfter = false)
        val dialog = c.current.value!!
        assertFalse(dialog.viaSystemRequest)
        c.setHostState(true, false)
        runCurrent()
        c.onShown(dialog)

        gates.value = setOf(GateId.NETWORK_RISK)
        runCurrent()
        assertNull(c.current.value)
        gates.value = emptySet()
        runCurrent()
        val again = c.current.value!!
        assertEquals(PromptId.NOTIFICATIONS, again.id)
        assertFalse(again.viaSystemRequest)
    }

    @Test
    fun `a failed action write keeps the rationale in memory, so a visible denial is not read as silent`() = runTest {
        val failing = FailingWrites(RecordingPreferencesDataStore())
        val failingStore = StartupPromptStore(failing, Gson())
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF, requestable = true)
        val c = StartupPromptCoordinator(failingStore, providers, sessions, { now }, { now }, backgroundScope).also {
            it.start()
            runCurrent()
        }
        startSession(c, rationale = true)
        val system = c.current.value!!
        c.onShown(system)

        failing.fail = true
        assertTrue("the user's tap still goes ahead", c.onActionTaken(system, rationaleBefore = true))
        failing.fail = false
        assertEquals(0L, failingStore.read()[PromptId.NOTIFICATIONS]?.pendingActionAt ?: 0L)

        // Second, visible denial: rationale true before, false after.
        c.onNotificationRequestResult(granted = false, rationaleAfter = false)
        assertNull("not mistaken for a silent result", c.current.value)
        val after = failingStore.read()[PromptId.NOTIFICATIONS]!!
        assertEquals(1, after.systemDenials)
        assertEquals(1, after.declines)
    }

    @Test
    fun `a trip whose write failed still uses the next session's budget once`() = runTest {
        val failing = FailingWrites(RecordingPreferencesDataStore())
        val failingStore = StartupPromptStore(failing, Gson())
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = StartupPromptCoordinator(failingStore, providers, sessions, { now }, { now }, backgroundScope).also {
            it.start()
            runCurrent()
        }
        startSession(c)
        val ticket = c.current.value!!
        failing.fail = true
        c.onShown(ticket)
        assertTrue(c.onActionTaken(ticket))
        endSession()
        failing.fail = false

        now += 5 * 60_000
        startSession(c)
        assertNull("own trip from memory", c.current.value)
        c.setHostState(false, false)
        runCurrent()
        endSession()
        startSession(c)
        assertEquals(PromptId.FULL_SCREEN, c.current.value?.id)
    }

    @Test
    fun `an aborted action records no decline and makes the prompt current again`() = runTest {
        eligible(PromptId.BACKGROUND_CONNECTION)
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        assertTrue(c.onActionTaken(ticket))
        c.onActionAborted(ticket)

        val again = c.current.value!!
        assertEquals(PromptId.BACKGROUND_CONNECTION, again.id)
        assertTrue(again.seq > ticket.seq)
        // The recreated host settles and reports again: nothing is reconciled as a return unfixed.
        c.setHostState(true, false)
        runCurrent()
        assertEquals(again, c.current.value)
        val record = record(PromptId.BACKGROUND_CONNECTION)!!
        assertEquals(0L, record.pendingActionAt)
        assertEquals(0, record.declines)

        // A second abort for the same ticket changes nothing.
        c.onActionAborted(ticket)
        assertEquals(again, c.current.value)
    }

    @Test
    fun `a stale tap on a preempted prompt records nothing`() = runTest {
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        gates.value = setOf(GateId.NETWORK_RISK)
        runCurrent()
        val preempted = record(PromptId.FULL_SCREEN)!!

        c.onDeclined(ticket)
        assertFalse(c.onActionTaken(ticket))
        assertEquals(preempted, record(PromptId.FULL_SCREEN))
    }
}
