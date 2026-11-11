package com.difft.android.push

import com.difft.android.chat.data.PushCustomContent
import com.difft.android.chat.messages.EnvelopToMessageProcessor
import com.difft.android.chat.messages.EnvelopeProcessResult
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.util.EnvelopDeserializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds





@OptIn(ExperimentalCoroutinesApi::class)
class FcmNormalMessageHandlerTest {

    private val messaging = TestMessagingPause().open()
    private val envelopToMessageProcessor = mockk<EnvelopToMessageProcessor>()
    private lateinit var handler: FcmNormalMessageHandler

    private val push = PushCustomContent(
        gid = null, locKey = null, msg = "AAAA", mutableContent = 0, notifyType = 0,
        passthrough = null, uid = "+10002", critical = 0,
    )

    @Before
    fun setUp() {
        mockkObject(EnvelopDeserializer)
        every { EnvelopDeserializer.deserializeFrom(any()) } returns Envelope.newBuilder().setTimestamp(5L).build()
        handler = FcmNormalMessageHandler(
            context = mockk(relaxed = true),
            messagingGate = messaging.gate,
            envelopToMessageProcessor = envelopToMessageProcessor,
            userManager = mockk(relaxed = true),
            messageNotificationUtil = mockk(relaxed = true),
            callDataManager = mockk(relaxed = true) { every { getCallListData() } returns emptyMap() },
            notificationRepostGuard = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `T26 a pause that begins before the launched coroutine runs makes it a no-op`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))

        handler.handle(push, scope)
        messaging.pauseMessaging()
        scope.testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { envelopToMessageProcessor.process(any(), any(), any()) }
    }

    @Test
    fun `T26 a coroutine admitted before the pause is processed and awaited by the close`() = runBlocking {
        val inProcess = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { envelopToMessageProcessor.process(any(), "fcm", any()) } coAnswers {
            inProcess.complete(Unit)
            release.await()
            EnvelopeProcessResult.Success(null)
        }

        val job = handler.handle(push, CoroutineScope(Dispatchers.IO))
        withTimeout(5.seconds) { inProcess.await() }
        messaging.pauseMessaging()
        val idle = async(Dispatchers.IO) { messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 5.seconds) }

        Thread.sleep(SETTLE_MS)
        assertFalse(idle.isCompleted, "the close must wait for the admitted FCM work")
        release.complete(Unit)

        assertTrue(idle.await())
        job.join()
        coVerify(exactly = 1) { envelopToMessageProcessor.process(any(), "fcm", any()) }
    }

    private companion object {
        const val SETTLE_MS = 200L
    }
}
