package com.difft.android.chat.message

import android.app.Application
import com.difft.android.PushActivityNoticeSendJobFactory
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.JobManager
import com.difft.android.chat.jobs.PushActivityNoticeSendJob
import com.difft.android.chat.video.exo.SimpleExoPlayerPool
import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.MessageActivityNoticeData
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Phase 4: verify [ActivityNoticeDispatcher.dispatchCopyNotice] propagates the new
 * [combinedForwardMode] parameter into [MessageActivityNoticeData], and that the early-return
 * guards still hold (empty authors / non-positive count → no job enqueued).
 *
 * NOTE: We resolve JobManager via [ApplicationDependencies.getJobManager] inside @Before
 * (not via a per-class companion mock). [ApplicationDependencies] is process-singleton and
 * `isInitialized()` short-circuits re-init — whichever test class loads first wins. Reading
 * the live instance and re-stubbing it here keeps this test order-independent. clearMocks
 * in @After leaves the JobManager mock empty so the next test's @Before re-stubs cleanly.
 */
class ActivityNoticeDispatcherTest {

    private lateinit var jobManager: JobManager
    private val factory = mockk<PushActivityNoticeSendJobFactory>(relaxed = true)
    private lateinit var dispatcher: ActivityNoticeDispatcher

    @Before
    fun setUp() {
        if (!ApplicationDependencies.isInitialized()) {
            ApplicationDependencies.init(
                mockk<Application>(relaxed = true),
                object : ApplicationDependencies.Provider {
                    override fun provideJobManager(): JobManager = mockk(relaxed = true)
                    override fun provideExoPlayerPool(): SimpleExoPlayerPool = mockk(relaxed = true)
                },
            )
        }
        jobManager = ApplicationDependencies.getJobManager()
        every { jobManager.add(any()) } just Runs

        dispatcher = ActivityNoticeDispatcher(factory)
    }

    @After
    fun tearDown() {
        clearMocks(jobManager, factory)
    }

    private companion object {
        // Operator id passed explicitly to dispatchCopyNotice — differs from every test
        // conversation so the Saved guard stays inert unless a test uses it as the source.
        const val MY_ID = "+myself"
    }

    @Test
    fun `dispatchCopyNotice — default mode is UNKNOWN preserved in payload`() {
        val conv = For.Account("+conv")
        val noticeSlot = slot<MessageActivityNoticeData>()
        every { factory.create(any(), any(), capture(noticeSlot)) } returns mockk<PushActivityNoticeSendJob>(relaxed = true)

        dispatcher.dispatchCopyNotice(
            sourceConversation = conv,
            sourceAuthorIds = listOf("+a"),
            myId = MY_ID,
            messageCount = 1,
        )

        verify(exactly = 1) { jobManager.add(any()) }
        assertEquals(MessageActivityNoticeData.Type.COPY, noticeSlot.captured.type)
        assertEquals(CombinedForwardMode.UNKNOWN, noticeSlot.captured.combinedForwardMode)
        assertEquals(listOf("+a"), noticeSlot.captured.sourceAuthorIds)
        assertEquals(1, noticeSlot.captured.messageCount)
    }

    @Test
    fun `dispatchCopyNotice — explicit SUB_COMBINED_FORWARD propagates into payload`() {
        val conv = For.Group("+group")
        val noticeSlot = slot<MessageActivityNoticeData>()
        every { factory.create(any(), any(), capture(noticeSlot)) } returns mockk<PushActivityNoticeSendJob>(relaxed = true)

        dispatcher.dispatchCopyNotice(
            sourceConversation = conv,
            sourceAuthorIds = listOf("+a", "+b"),
            myId = MY_ID,
            messageCount = 2,
            combinedForwardMode = CombinedForwardMode.SUB_COMBINED_FORWARD,
        )

        verify(exactly = 1) { jobManager.add(any()) }
        assertEquals(CombinedForwardMode.SUB_COMBINED_FORWARD, noticeSlot.captured.combinedForwardMode)
    }

    @Test
    fun `dispatchCopyNotice — explicit CONTAINS_COMBINED_FORWARD propagates`() {
        val conv = For.Account("+conv")
        val noticeSlot = slot<MessageActivityNoticeData>()
        every { factory.create(any(), any(), capture(noticeSlot)) } returns mockk<PushActivityNoticeSendJob>(relaxed = true)

        dispatcher.dispatchCopyNotice(
            sourceConversation = conv,
            sourceAuthorIds = listOf("+a"),
            myId = MY_ID,
            messageCount = 3,
            combinedForwardMode = CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
        )

        assertEquals(CombinedForwardMode.CONTAINS_COMBINED_FORWARD, noticeSlot.captured.combinedForwardMode)
    }

    @Test
    fun `dispatchCopyNotice — explicit ALL_COMBINED_FORWARD propagates`() {
        val conv = For.Account("+conv")
        val noticeSlot = slot<MessageActivityNoticeData>()
        every { factory.create(any(), any(), capture(noticeSlot)) } returns mockk<PushActivityNoticeSendJob>(relaxed = true)

        dispatcher.dispatchCopyNotice(
            sourceConversation = conv,
            sourceAuthorIds = listOf("+a"),
            myId = MY_ID,
            messageCount = 5,
            combinedForwardMode = CombinedForwardMode.ALL_COMBINED_FORWARD,
        )

        assertEquals(CombinedForwardMode.ALL_COMBINED_FORWARD, noticeSlot.captured.combinedForwardMode)
    }

    @Test
    fun `dispatchCopyNotice — empty author list drops silently (no job enqueued)`() {
        dispatcher.dispatchCopyNotice(
            sourceConversation = For.Account("+conv"),
            sourceAuthorIds = emptyList(),
            myId = MY_ID,
            messageCount = 1,
            combinedForwardMode = CombinedForwardMode.ALL_COMBINED_FORWARD,
        )
        verify(exactly = 0) { jobManager.add(any()) }
        verify(exactly = 0) { factory.create(any(), any(), any()) }
    }

    @Test
    fun `dispatchCopyNotice — non-positive count drops silently`() {
        dispatcher.dispatchCopyNotice(
            sourceConversation = For.Account("+conv"),
            sourceAuthorIds = listOf("+a"),
            myId = MY_ID,
            messageCount = 0,
        )
        verify(exactly = 0) { jobManager.add(any()) }

        dispatcher.dispatchCopyNotice(
            sourceConversation = For.Account("+conv"),
            sourceAuthorIds = listOf("+a"),
            myId = MY_ID,
            messageCount = -1,
        )
        verify(exactly = 0) { jobManager.add(any()) }
    }

    @Test
    fun `dispatchCopyNotice — Saved (source equals myId) drops silently`() {
        // PRD v2.0 §改动1 条件②: copying inside the user's own Saved conversation has no audience.
        // A foreign author is supplied so only the Saved guard can suppress it.
        dispatcher.dispatchCopyNotice(
            sourceConversation = For.Account(MY_ID),
            sourceAuthorIds = listOf("+a"),
            myId = MY_ID,
            messageCount = 1,
        )
        verify(exactly = 0) { jobManager.add(any()) }
    }
}
