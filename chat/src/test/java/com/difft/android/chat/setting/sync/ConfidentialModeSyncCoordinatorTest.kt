package com.difft.android.chat.setting.sync

import com.difft.android.SettingSyncJobFactory
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.jobs.SettingSyncJob
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import difft.android.messageserialization.For
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfidentialModeSyncCoordinatorTest {

    private val field = SyncField.CONFIDENTIAL_MODE
    private val q = FakeQueue()
    private val dbRoomStore: DBRoomStore = mockk(relaxed = true)
    private val manager: ConversationSettingsManager = mockk(relaxed = true)
    private val factory: SettingSyncJobFactory = mockk()
    private val job: SettingSyncJob = mockk(relaxed = true)

    private val coordinator = ConfidentialModeSyncCoordinator(dbRoomStore, q.state, manager, factory)

    @Before
    fun setUp() {
        mockkObject(ServerTimeProvider)
        every { ServerTimeProvider.nowMillis() } returns 5_000L
        every { factory.create(any(), any(), any(), any(), any(), any(), any()) } returns job
        every { dbRoomStore.getRoom(ROOM) } returns room()
        q.queue()
    }

    @Test
    fun `a missing row is created first, from the given For or from what the id resolves to`() = runTest {
        every { dbRoomStore.getRoom(ROOM) } returns null
        every { dbRoomStore.resolveFor(ROOM) } returns For.Group(ROOM)

        assertTrue(coordinator.setConfidentialMode(ROOM, 1))
        verifyOrder {
            dbRoomStore.createRoomIfNotExist(For.Group(ROOM))
            dbRoomStore.updateConfidentialMode(ROOM, 1)
            q.jobManager.add(job)
        }

        assertTrue(coordinator.setConfidentialMode(For.Account(ROOM), 1))
        verify { dbRoomStore.createRoomIfNotExist(For.Account(ROOM)) }
    }

    @Test
    fun `an existing row is not recreated`() = runTest {
        assertTrue(coordinator.setConfidentialMode(ROOM, 1))
        verify(exactly = 0) { dbRoomStore.createRoomIfNotExist(any()) }
    }

    @After
    fun tearDown() = unmockkObject(ServerTimeProvider)

    @Test
    fun `writes the row, records the intent, emits, then enqueues a SEND job`() = runTest {
        assertTrue(coordinator.setConfidentialMode(ROOM, 1))

        verifyOrder {
            dbRoomStore.updateConfidentialMode(ROOM, 1)
            manager.emitConversationSettingUpdate(conversationId = ROOM, confidentialMode = 1)
            factory.create(null, ROOM, field, 1, 5_000L, SettingSyncJob.Phase.SEND, 0)
            q.jobManager.add(job)
        }
        assertEquals(5_000L, q.state.activeIntent(ROOM, field)?.ts)
    }

    @Test
    fun `a failed row write leaves no intent, no event and no job`() = runTest {
        every { dbRoomStore.updateConfidentialMode(ROOM, 1) } throws IllegalStateException("db")

        assertFalse(coordinator.setConfidentialMode(ROOM, 1))

        assertNull(q.state.activeIntent(ROOM, field))
        verify(exactly = 0) { manager.emitConversationSettingUpdate(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { q.jobManager.add(any()) }
    }

    @Test
    fun `operation time never goes backwards relative to a pending job`() = runTest {
        q.queue(pendingSpec("a", ts = 9_000))

        coordinator.setConfidentialMode(ROOM, 0)

        verify { factory.create(null, ROOM, field, 0, 9_001L, SettingSyncJob.Phase.SEND, 0) }
    }
}
