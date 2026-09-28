package com.difft.android.migration.v2.login

import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.setting.ConversationSettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warm-up waits for the first contact and group sync, but only up to its bound; a sync that
 * outlives the bound keeps running and the chat list opens without it, exactly as before.
 */
class PostRestoreWarmUpTest {

    private val groupUtil = mockk<GroupUtil>()
    private val settings = mockk<ConversationSettingsManager> {
        every { syncConversationSettings(any()) } just Runs
    }

    private var roomsNamed = 0

    private fun warmUp(contacts: suspend () -> Boolean) = PostRestoreWarmUp(groupUtil, settings).apply {
        awaitContacts = contacts
        nameRooms = { roomsNamed++; 4 }
    }

    @Test
    fun `both syncs landing in time reports ready and names the rooms once`() = runTest {
        coEvery { groupUtil.syncAllGroupAndAllGroupMembers(forceFetch = false, syncMembers = true) } coAnswers { delay(300) }
        val warmUp = warmUp { delay(500); true }.also { it.scope = backgroundScope }

        assertTrue(warmUp.run(timeoutMs = 5_000))
        assertEquals(1, roomsNamed)
        verify(exactly = 1) { settings.syncConversationSettings() }
        coVerify(exactly = 1) { groupUtil.syncAllGroupAndAllGroupMembers(forceFetch = false, syncMembers = true) }
    }

    @Test
    fun `rooms are not named ahead of the directory`() = runTest {
        coEvery { groupUtil.syncAllGroupAndAllGroupMembers(any(), any()) } coAnswers { }
        val warmUp = warmUp { delay(20_000); true }.also { it.scope = backgroundScope }

        assertFalse(warmUp.run(timeoutMs = 5_000))
        assertEquals(0, roomsNamed)
    }

    @Test
    fun `a failing room naming pass does not fail the warm-up`() = runTest {
        coEvery { groupUtil.syncAllGroupAndAllGroupMembers(any(), any()) } coAnswers { }
        val warmUp = warmUp { true }.also {
            it.scope = backgroundScope
            it.nameRooms = { error("db closed") }
        }

        assertTrue(warmUp.run(timeoutMs = 5_000))
    }

    @Test
    fun `a sync outliving the bound is left running, and the caller goes on`() = runTest {
        var contactsFinished = false
        coEvery { groupUtil.syncAllGroupAndAllGroupMembers(any(), any()) } coAnswers { }
        val warmUp = warmUp { delay(20_000); contactsFinished = true; true }.also { it.scope = backgroundScope }

        assertFalse(warmUp.run(timeoutMs = 5_000))
        assertFalse(contactsFinished)
        // Background work does not count towards "idle"; move the clock past the sync's own delay.
        advanceTimeBy(20_001)
        runCurrent()
        assertTrue(contactsFinished)
    }

    @Test
    fun `a failed contact sync is not ready and does not throw`() = runTest {
        coEvery { groupUtil.syncAllGroupAndAllGroupMembers(any(), any()) } coAnswers { }
        val warmUp = warmUp { error("directory down") }.also { it.scope = backgroundScope }

        assertEquals(false, warmUp.run(timeoutMs = 5_000))
    }
}
