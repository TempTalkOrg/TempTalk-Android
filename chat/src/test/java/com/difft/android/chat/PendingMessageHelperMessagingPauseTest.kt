package com.difft.android.chat

import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.chat.messages.EnvelopToMessageProcessor
import com.difft.android.chat.messages.EnvelopeProcessResult
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.responses.PendingMessage
import com.difft.android.network.responses.PendingMessageResponse
import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.difft.app.database.WCDBUpdateService
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue

/** G-R6 (#1382): the REST pull holds one ticket per page and is deferred while messaging is closed. */
class PendingMessageHelperMessagingPauseTest {

    private val messaging = TestMessagingPause().open()
    private val httpService = mockk<HttpService>(relaxed = true)
    private val httpClient = mockk<ChativeHttpClient>()
    private val envelopToMessageProcessor = mockk<EnvelopToMessageProcessor>()
    private lateinit var helper: PendingMessageHelper

    @Before
    fun setUp() {
        resetPullState()
        mockkObject(WCDBUpdateService)
        every { WCDBUpdateService.updatingRooms() } just Runs
        every { httpClient.httpService } returns httpService
        helper = PendingMessageHelper(httpClient, envelopToMessageProcessor, mockk(relaxed = true), mockk(relaxed = true), messaging.gate)
    }

    @After
    fun tearDown() {
        unmockkAll()
        resetPullState()
    }

    /** The reentry lock and the deferred request are process-wide; isolate each test. */
    private fun resetPullState() {
        val type = PendingMessageHelper::class.java
        (type.getDeclaredField("isProcessing").apply { isAccessible = true }.get(null) as AtomicBoolean).set(false)
        (type.getDeclaredField("deferredPull").apply { isAccessible = true }.get(null) as AtomicReference<*>).set(null)
    }

    private suspend fun joinAll(job: Job) {
        while (true) {
            val active = job.children.filter { it.isActive }.toList()
            if (active.isEmpty()) return
            active.forEach { it.join() }
        }
    }

    private fun pending(ts: Long) = PendingMessage(
        type = 1, relay = null, timestamp = ts, source = "+1555", sourceDevice = 1,
        content = null, systemShowTimestamp = ts, sequenceId = 0L, notifySequenceId = 0L,
        msgType = 1, conversation = null, identityKey = null, peerContext = null,
    )

    @Test
    fun `T14 a page in progress at the pause is stored and deleted, the next page is never fetched`() = runBlocking {
        coEvery { httpService.getPendingMessage() } returns PendingMessageResponse(listOf(pending(100L)), more = true)
        coEvery { envelopToMessageProcessor.process(any(), "PendingMessageHelper", any()) } coAnswers {
            // The pause begins while this page is being processed.
            messaging.pauseMessaging()
            EnvelopeProcessResult.Success(null)
        }

        assertTrue(helper.obtainPendingMessageAndSave())

        coVerify(exactly = 1) { httpService.getPendingMessage() }
        coVerify(exactly = 1) { envelopToMessageProcessor.process(any(), any(), any()) }
        coVerify(exactly = 1) { httpService.removePendingMessage("+1555", "100") }
    }

    @Test
    fun `T11 a pull requested while paused fetches nothing and runs once after resume`() = runBlocking {
        coEvery { httpService.getPendingMessage() } returns PendingMessageResponse(emptyList(), more = false)
        messaging.pauseMessaging()

        val job = Job()
        helper.schedulePendingMessageWork(PendingMessageHelper.PullTrigger.FCM, CoroutineScope(job))
        job.children.toList().forEach { it.join() }
        coVerify(exactly = 0) { httpService.getPendingMessage() }

        messaging.resumeMessaging()
        helper.runDeferredPull(CoroutineScope(job))
        job.children.toList().forEach { it.join() }
        helper.runDeferredPull(CoroutineScope(job)) // already consumed: no second pull
        job.children.toList().forEach { it.join() }

        coVerify(exactly = 1) { httpService.getPendingMessage() }
    }

    @Test
    fun `a pull deferred just as messaging opens still runs`() = runBlocking {
        coEvery { httpService.getPendingMessage() } returns PendingMessageResponse(emptyList(), more = false)
        // The caller sees the gate closed; it opens (and the resume's deferred run finds nothing) before the request is recorded.
        val gate = spyk(messaging.gate)
        every { gate.isOpen } returnsMany listOf(false, true)
        val racing = PendingMessageHelper(httpClient, envelopToMessageProcessor, mockk(relaxed = true), mockk(relaxed = true), gate)

        val job = Job()
        racing.schedulePendingMessageWork(PendingMessageHelper.PullTrigger.FCM, CoroutineScope(job))
        job.children.toList().forEach { it.join() }

        coVerify(exactly = 1) { httpService.getPendingMessage() }
    }

    @Test
    fun `a pull deferred by a running pull still runs when messaging opens before that pull ends`() = runBlocking {
        var pulls = 0
        coEvery { httpService.getPendingMessage() } answers {
            // The pause begins during the first page, so the running pull is refused its next page and defers it.
            if (pulls++ == 0) {
                messaging.pauseMessaging()
                PendingMessageResponse(emptyList(), more = true)
            } else {
                PendingMessageResponse(emptyList(), more = false)
            }
        }
        val job = Job()
        val scope = CoroutineScope(job)
        val healthCounters = mockk<KeepAliveHealthCounters>()
        lateinit var racing: PendingMessageHelper
        every { healthCounters.onPullResult(any()) } answers {
            // Messaging opens and its deferred run fires while the refused pull still holds the reentry lock.
            messaging.resumeMessaging()
            racing.runDeferredPull(scope)
        }
        racing = PendingMessageHelper(httpClient, envelopToMessageProcessor, mockk(relaxed = true), healthCounters, messaging.gate)

        racing.schedulePendingMessageWork(PendingMessageHelper.PullTrigger.FCM, scope)
        joinAll(job)

        coVerify(exactly = 2) { httpService.getPendingMessage() }
    }
}
