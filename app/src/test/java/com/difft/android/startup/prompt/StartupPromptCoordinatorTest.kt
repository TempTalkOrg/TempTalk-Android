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
 * Choice, budget, gates and outcomes of the startup prompt coordinator (#1355 §4-§5), against the
 * real store over an in-memory DataStore and a controlled clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupPromptCoordinatorTest : StartupPromptCoordinatorTestBase() {

    @Test
    fun `first eligible in priority order wins`() = runTest {
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        val c = coordinator()
        startSession(c)
        assertEquals(PromptId.NOTIFICATIONS, c.current.value?.id)
        assertEquals(ReadinessIssue.APP_NOTIFICATIONS_OFF, c.current.value?.issue)
    }

    @Test
    fun `an answer that cannot be persisted still closes the prompt for the session`() = runTest {
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val failing = spyk(store)
        coEvery { failing.update(any()) } throws IOException("disk full")
        val c = StartupPromptCoordinator(failing, providers, sessions, { now }, { now }, backgroundScope).also {
            it.start()
            runCurrent()
        }
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onDeclined(ticket)
        runCurrent()
        c.setHostState(true, false)
        runCurrent()
        assertNull(c.current.value)
    }

    @Test
    fun `nothing is chosen until the host is presentable`() = runTest {
        eligible(PromptId.BACKGROUND_CONNECTION)
        val c = coordinator()
        startSession(c, presentable = false)
        assertNull(c.current.value)
        c.setHostState(true, false)
        runCurrent()
        assertEquals(PromptId.BACKGROUND_CONNECTION, c.current.value?.id)
    }

    @Test
    fun `one prompt per session, the next session shows the next one`() = runTest {
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onDeclined(ticket)
        assertNull(c.current.value)
        c.setHostState(true, false)
        runCurrent()
        assertNull("budget used", c.current.value)

        endSession()
        startSession(c)
        assertEquals("notifications snoozed for a day", PromptId.FULL_SCREEN, c.current.value?.id)
        assertEquals(1, record(PromptId.NOTIFICATIONS)?.declines)
        assertEquals(now + day, record(PromptId.NOTIFICATIONS)?.snoozeUntil)
    }

    @Test
    fun `unknown keeps the record untouched`() = runTest {
        val stored = PromptRecord(declines = 2, snoozeUntil = now - 1, shownCount = 2)
        store.update { mapOf(PromptId.BACKGROUND_CONNECTION to stored) }
        answers[PromptId.BACKGROUND_CONNECTION] = ProviderAnswer.UNKNOWN
        val c = coordinator()
        startSession(c)
        assertNull(c.current.value)
        assertEquals(stored, record(PromptId.BACKGROUND_CONNECTION))
    }

    @Test
    fun `late result shows while the budget is unused and not after it is used`() = runTest {
        val c = coordinator()
        startSession(c)
        assertNull(c.current.value)
        eligible(PromptId.BACKGROUND_CONNECTION)
        changes.emit(Unit)
        runCurrent()
        val ticket = c.current.value!!
        assertEquals(PromptId.BACKGROUND_CONNECTION, ticket.id)
        c.onShown(ticket)
        c.onDeclined(ticket)

        eligible(PromptId.OPTIONAL_UPDATE)
        changes.emit(Unit)
        runCurrent()
        assertNull(c.current.value)
    }

    @Test
    fun `gate preempts without using the budget or touching the record`() = runTest {
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)

        gates.value = setOf(GateId.FORCED_UPDATE)
        runCurrent()
        assertNull(c.current.value)
        val afterPreempt = record(PromptId.NOTIFICATIONS)!!
        assertEquals(0, afterPreempt.declines)
        assertEquals(0L, afterPreempt.snoozeUntil)
        assertEquals(0L, afterPreempt.showingSince)

        gates.value = emptySet()
        runCurrent()
        assertEquals(PromptId.NOTIFICATIONS, c.current.value?.id)
        assertTrue(c.current.value!!.seq > ticket.seq)
    }

    @Test
    fun `no prompt is chosen while a gate is active`() = runTest {
        gates.value = setOf(GateId.SECURITY)
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        val c = coordinator()
        startSession(c)
        assertNull(c.current.value)
    }

    @Test
    fun `resolved removes the record and a revocation starts at step zero`() = runTest {
        store.update { mapOf(PromptId.FULL_SCREEN to PromptRecord(declines = 3, snoozeUntil = now + 7 * day)) }
        answers[PromptId.FULL_SCREEN] = ProviderAnswer.NOT_ELIGIBLE
        val c = coordinator()
        startSession(c)
        assertNull(record(PromptId.FULL_SCREEN))

        endSession()
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        startSession(c)
        val ticket = c.current.value!!
        assertEquals(PromptId.FULL_SCREEN, ticket.id)
        c.onShown(ticket)
        c.onDeclined(ticket)
        assertEquals(1, record(PromptId.FULL_SCREEN)?.declines)
        assertEquals(now + day, record(PromptId.FULL_SCREEN)?.snoozeUntil)
    }

    @Test
    fun `own trip uses the next session's budget after a stop and after process death`() = runTest {
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onActionTaken(ticket)
        endSession()

        // Back from Settings: notifications unreadable for now, another prompt is eligible.
        now += 5 * 60_000
        answers[PromptId.NOTIFICATIONS] = ProviderAnswer.UNKNOWN
        eligible(PromptId.BACKGROUND_CONNECTION)
        startSession(c)
        assertNull(c.current.value)
        assertTrue(record(PromptId.NOTIFICATIONS)!!.pendingActionAt != 0L)

        // Process died in Settings: a fresh coordinator reads the persisted pending action.
        endSession()
        val revived = coordinator()
        startSession(revived)
        assertNull(revived.current.value)
    }

    @Test
    fun `pending action is reconciled to a decline or removed`() = runTest {
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onActionTaken(ticket)
        // Back without fixing: one ladder step (D2).
        c.setHostState(true, false)
        runCurrent()
        val declined = record(PromptId.NOTIFICATIONS)!!
        assertEquals(1, declined.declines)
        assertEquals(0L, declined.pendingActionAt)

        store.update { mapOf(PromptId.FULL_SCREEN to PromptRecord(pendingActionAt = now)) }
        answers[PromptId.FULL_SCREEN] = ProviderAnswer.NOT_ELIGIBLE
        c.setHostState(true, false)
        runCurrent()
        assertNull(record(PromptId.FULL_SCREEN))
    }

    @Test
    fun `background while showing keeps the ticket and records nothing`() = runTest {
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        val shown = record(PromptId.FULL_SCREEN)!!
        c.setHostState(false, false)
        runCurrent()
        endSession()
        assertEquals("still current", ticket, c.current.value)
        val after = record(PromptId.FULL_SCREEN)!!
        assertEquals(shown, after)
        assertEquals(0L, after.snoozeUntil)
        assertEquals(0, after.declines)
        assertTrue(after.showingSince != 0L)
    }

    @Test
    fun `the next session keeps the carried prompt and shows no second one`() = runTest {
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        eligible(PromptId.OPTIONAL_UPDATE)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.setHostState(false, false)
        runCurrent()
        endSession()

        now += 2 * day
        startSession(c)
        assertEquals("same ticket, re-rendered by the host", ticket, c.current.value)
        c.onShown(ticket)
        assertEquals(1, record(PromptId.FULL_SCREEN)?.shownCount)
        c.onDeclined(ticket)
        assertEquals(1, record(PromptId.FULL_SCREEN)?.declines)
        c.setHostState(true, false)
        runCurrent()
        assertNull("the carried prompt was this session's prompt", c.current.value)
    }

    @Test
    fun `a gate during a carried prompt preempts it and returns the budget`() = runTest {
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        endSession()
        startSession(c)
        assertEquals(ticket, c.current.value)

        gates.value = setOf(GateId.SECURITY)
        runCurrent()
        assertNull(c.current.value)
        assertEquals(0L, record(PromptId.FULL_SCREEN)?.showingSince)
        gates.value = emptySet()
        runCurrent()
        val again = c.current.value!!
        assertEquals(PromptId.FULL_SCREEN, again.id)
        assertTrue(again.seq > ticket.seq)
    }

    @Test
    fun `showing left over from a dead process shows again without a snooze`() = runTest {
        store.update { mapOf(PromptId.FULL_SCREEN to PromptRecord(shownCount = 1, showingSince = now - 1_000)) }
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        assertEquals(PromptId.FULL_SCREEN, c.current.value?.id)
        val record = record(PromptId.FULL_SCREEN)!!
        assertEquals(0L, record.snoozeUntil)
        assertEquals(0, record.declines)
        assertEquals(0L, record.showingSince)
    }

    @Test
    fun `recreation re-render records the show once`() = runTest {
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.setHostState(false, false)
        c.setHostState(true, false)
        runCurrent()
        c.onShown(ticket)
        assertEquals(ticket, c.current.value)
        assertEquals(1, record(PromptId.FULL_SCREEN)?.shownCount)
    }

    @Test
    fun `late prompt waits for the open conversation, a session-start prompt does not`() = runTest {
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c, dualPane = true)
        assertEquals("session-start prompt ignores the pane", PromptId.FULL_SCREEN, c.current.value?.id)
        c.onDeclined(c.current.value!!)

        endSession()
        answers.clear()
        startSession(c, dualPane = true)
        now += 6_000
        eligible(PromptId.BACKGROUND_CONNECTION)
        changes.emit(Unit)
        runCurrent()
        assertNull("late prompt waits", c.current.value)

        c.setHostState(true, false)
        runCurrent()
        assertEquals(PromptId.BACKGROUND_CONNECTION, c.current.value?.id)
    }

    // ---------- A lower prompt waits for a higher UNKNOWN one, capped at 15 s ----------

    @Test
    fun `background connection unknown holds the full-screen prompt`() = runTest {
        answers[PromptId.BACKGROUND_CONNECTION] = ProviderAnswer.UNKNOWN
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        assertNull(c.current.value)
    }

    @Test
    fun `held prompt shows once the higher one resolves not eligible`() = runTest {
        answers[PromptId.BACKGROUND_CONNECTION] = ProviderAnswer.UNKNOWN
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        now += 3_000
        answers[PromptId.BACKGROUND_CONNECTION] = ProviderAnswer.NOT_ELIGIBLE
        changes.emit(Unit)
        runCurrent()
        assertEquals(PromptId.FULL_SCREEN, c.current.value?.id)
    }

    @Test
    fun `higher prompt wins when it resolves eligible`() = runTest {
        answers[PromptId.BACKGROUND_CONNECTION] = ProviderAnswer.UNKNOWN
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        now += 3_000
        eligible(PromptId.BACKGROUND_CONNECTION)
        changes.emit(Unit)
        runCurrent()
        assertEquals(PromptId.BACKGROUND_CONNECTION, c.current.value?.id)
    }

    @Test
    fun `after the cap the best definite prompt is shown`() = runTest {
        answers[PromptId.BACKGROUND_CONNECTION] = ProviderAnswer.UNKNOWN
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        now += 14_000
        advanceTimeBy(14_000)
        runCurrent()
        assertNull("still within the cap", c.current.value)

        now += 1_000
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(PromptId.FULL_SCREEN, c.current.value?.id)
    }

    @Test
    fun `notifications are never delayed by lower unknown entries`() = runTest {
        eligible(PromptId.NOTIFICATIONS, ReadinessIssue.APP_NOTIFICATIONS_OFF)
        answers[PromptId.BACKGROUND_CONNECTION] = ProviderAnswer.UNKNOWN
        answers[PromptId.OPTIONAL_UPDATE] = ProviderAnswer.UNKNOWN
        val c = coordinator()
        startSession(c)
        assertEquals(PromptId.NOTIFICATIONS, c.current.value?.id)
    }

    @Test
    fun `a higher result after a lower prompt was shown waits for the next session`() = runTest {
        answers[PromptId.BACKGROUND_CONNECTION] = ProviderAnswer.UNKNOWN
        eligible(PromptId.FULL_SCREEN, ReadinessIssue.FULL_SCREEN_DENIED)
        val c = coordinator()
        startSession(c)
        now += 15_000
        advanceTimeBy(15_001)
        runCurrent()
        val ticket = c.current.value!!
        c.onShown(ticket)
        c.onDeclined(ticket)

        eligible(PromptId.BACKGROUND_CONNECTION)
        changes.emit(Unit)
        runCurrent()
        assertNull(c.current.value)

        endSession()
        startSession(c)
        assertEquals(PromptId.BACKGROUND_CONNECTION, c.current.value?.id)
    }
}
