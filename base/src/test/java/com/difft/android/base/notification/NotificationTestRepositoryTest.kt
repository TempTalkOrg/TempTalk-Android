package com.difft.android.base.notification

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull


class NotificationTestRepositoryTest {
    private lateinit var store: NotificationTestStore
    private lateinit var repository: NotificationTestRepository
    private val runId = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        store = mockk()
        val clock = mockk<NotificationTestClock> { every { nowMs() } returns 2_000L }
        repository = NotificationTestRepository(mockk(), store, clock)
    }

    @Test
    fun `the readiness read folds in the seen categories`() = runBlocking {
        val provider = mockk<NotificationReadinessProvider>()
        val untouched = ChannelReading(android.app.NotificationManager.IMPORTANCE_HIGH, hasSound = true, userSetImportance = false)
        every { provider.readInput() } returns ReadinessInput(
            sdkInt = 34,
            postNotificationsGranted = true,
            appNotificationsEnabled = true,
            messageGroupBlocked = false,
            messageChannel = untouched,
            callChannel = untouched,
            criticalChannel = untouched,
            canUseFullScreenIntent = true,
            oemBrand = OemBrand.XIAOMI,
        )
        coEvery { store.read() } returns emptyMap()
        coEvery { store.readSeenCategories() } returns setOf(NotificationChannelIds.CALL)
        val readiness = NotificationTestRepository(provider, store, mockk(relaxed = true)).readReadiness()
        assertEquals(ReadinessIssue.MESSAGE_CATEGORY_NEVER_SET, readiness.item(ReadinessItemId.N2)?.issue)
        assertEquals(ReadinessStatus.OK, readiness.item(ReadinessItemId.C1)?.status)
    }

    @Test
    fun `cold screen success uses the durable ticket without reading current settings`() = runBlocking {
        coEvery { store.updateCall(runId, 2_000L, true) } returns NotificationTestCallUpdate.RECORDED
        assertEquals(NotificationTestCallUpdate.RECORDED, repository.recordCallShown(runId))
        assertEquals(runId, repository.callShownRun.value)
        coVerify(exactly = 0) { store.write(any(), any()) }
    }

    @Test
    fun `a rejected old ticket cannot raise the shown signal`() = runBlocking {
        coEvery { store.updateCall(runId, 2_000L, true) } returns NotificationTestCallUpdate.REJECTED
        assertEquals(NotificationTestCallUpdate.REJECTED, repository.recordCallShown(runId))
        assertNull(repository.callShownRun.value)
    }

    @Test
    fun `a disk failure cannot masquerade as shown or as a recorded timeout`() = runBlocking {
        coEvery { store.updateCall(any(), any(), any()) } throws IOException("disk")
        assertEquals(NotificationTestCallUpdate.PERSISTENCE_FAILED, repository.recordCallShown(runId))
        assertEquals(NotificationTestCallUpdate.PERSISTENCE_FAILED, repository.recordCallTimeout(runId))
        assertNull(repository.callShownRun.value)
    }

    @Test
    fun `an already shown ticket wins over a timeout`() = runBlocking {
        coEvery { store.updateCall(runId, 2_000L, false) } returns NotificationTestCallUpdate.ALREADY_SHOWN
        assertEquals(NotificationTestCallUpdate.ALREADY_SHOWN, repository.recordCallTimeout(runId))
    }
}
