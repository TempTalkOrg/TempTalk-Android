package com.difft.android.chat.jobs

import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.chat.common.SendType
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.chat.util.DataMessageCreator
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.messages.SendMessageResult
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.RateLimitException
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import difft.android.messageserialization.For
import difft.android.messageserialization.model.RealSource
import difft.android.messageserialization.model.ScreenShot
import difft.android.messageserialization.model.TextMessage
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.io.IOException
import kotlin.test.assertFailsWith









@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushScreenShotSendJobTest {

    private lateinit var messageSender: NewSignalServiceMessageSender
    private lateinit var dataMessageCreator: DataMessageCreator
    private lateinit var rowWriter: ScreenShotRowWriter
    private val groupUtil = mockk<GroupUtil>(relaxed = true)

    
    private val markedSent = mutableListOf<TextMessage>()

    
    private val gson: Gson = run {
        val valueAdapter = RuntimeTypeAdapterFactory.of(For::class.java)
            .registerSubtype(For.Account::class.java)
            .registerSubtype(For.Group::class.java)
        GsonBuilder().registerTypeAdapterFactory(valueAdapter).create()
    }

    private val myId = "+10000000001"
    private val peerId = "+10000000002"
    private val groupId = "group-abc"
    private val timeStamp = 1_700_000_000_000L
    private val screenshotText = "You took a screenshot!"

    
    private val serverSystemShowTimestamp = timeStamp + 137L
    private val serverNotifySequenceId = 42L
    private val serverSequenceId = 99L

    @Before
    fun setUp() {
        markedSent.clear()
        messageSender = mockk()
        dataMessageCreator = mockk()
        rowWriter = mockk(relaxed = true)

        
        
        every { rowWriter.markSent(capture(markedSent)) } just Runs

        every { dataMessageCreator.createFrom(any()) } returns
            SignalServiceProtos.DataMessage.newBuilder().setTimestamp(timeStamp).build()
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any(), any())
        } returns successResult()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `onAdded shows the pending row immediately`() {
        val original = screenshotMessage(For.Account(peerId))

        job(original).onAdded()

        verify(exactly = 1) {
            rowWriter.insertPending(match { it.id == original.id && it.sendType == SendType.Sending.rawValue })
        }
    }

    @Test
    fun `onAdded insert failure is swallowed — the send still goes out`() {
        every { rowWriter.insertPending(any()) } throws RuntimeException("simulated DB write failure")

        
        job(screenshotMessage(For.Account(peerId))).onAdded()
    }

    @Test
    fun `server accepted — marks the row Sent with the server ordering keys`() = runTest {
        val original = screenshotMessage(For.Account(peerId))
        val job = job(original)

        job.onPushSend()

        val stored = markedSent.single()
        
        
        assertEquals(original.id, stored.id)
        assertEquals(timeStamp, stored.timeStamp)
        assertEquals(For.Account(peerId), stored.forWhat)
        assertEquals(SendType.Sent.rawValue, stored.sendType)
        
        assertEquals(serverSystemShowTimestamp, stored.systemShowTimestamp)
        assertEquals(serverNotifySequenceId, stored.notifySequenceId)
        assertEquals(serverSequenceId, stored.sequenceId)
        
        
        assertNotNull(stored.screenShot)
        assertEquals(screenshotText, stored.text)
        verify(exactly = 0) { rowWriter.withdraw(any()) }
    }

    @Test
    fun `group target also marks the row Sent`() = runTest {
        val job = job(screenshotMessage(For.Group(groupId)))

        job.onPushSend()

        val stored = markedSent.single()
        coVerify(exactly = 1) { groupUtil.clearExpiry(groupId, "sent") }
        assertEquals(For.Group(groupId), stored.forWhat)
        assertEquals(SendType.Sent.rawValue, stored.sendType)
    }

    @Test
    fun `rejected group screenshot does not clear expiry`() = runTest {
        val rejected = mockk<SendMessageResult> { every { success } returns null }
        coEvery { messageSender.sendDataMessage(any(), any(), any(), any(), any()) } returns rejected
        job(screenshotMessage(For.Group(groupId))).onPushSend()
        coVerify(exactly = 0) { groupUtil.clearExpiry(any(), any()) }
    }

    @Test
    fun `direct screenshot does not clear group expiry`() = runTest {
        job(screenshotMessage(For.Account(peerId))).onPushSend()
        coVerify(exactly = 0) { groupUtil.clearExpiry(any(), any()) }
    }

    @Test
    fun `server rejected — row withdrawn and no throw`() = runTest {
        val rejected = mockk<SendMessageResult>()
        every { rejected.success } returns null
        coEvery { messageSender.sendDataMessage(any(), any(), any(), any(), any()) } returns rejected

        val original = screenshotMessage(For.Account(peerId))

        
        job(original).onPushSend()

        verify(exactly = 1) { rowWriter.withdraw(match { it.id == original.id }) }
        assertTrue(markedSent.isEmpty())
    }

    @Test
    fun `sender IOException propagates and the pending row stays`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any(), any())
        } throws IOException("simulated network failure")

        val job = job(screenshotMessage(For.Account(peerId)))

        assertFailsWith<IOException> { job.onPushSend() }

        verify(exactly = 0) { rowWriter.withdraw(any()) }
        assertTrue(markedSent.isEmpty())
    }

    @Test
    fun `432 rejection is permanent — no throw and row withdrawn`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any(), any())
        } throws NonSuccessfulResponseCodeException(432, "non-friend daily limit")

        val job = job(screenshotMessage(For.Account(peerId)))

        
        
        job.onPushSend()

        verify(exactly = 1) { rowWriter.withdraw(any()) }
        assertTrue(markedSent.isEmpty())
    }

    @Test
    fun `rate limit 429 still propagates so the job retries`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any(), any())
        } throws RateLimitException(429, "rate limit exceeded")

        val job = job(screenshotMessage(For.Account(peerId)))

        
        assertFailsWith<RateLimitException> { job.onPushSend() }

        verify(exactly = 0) { rowWriter.withdraw(any()) }
    }

    @Test
    fun `proof required 428 still propagates so the Retry-After backoff applies`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any(), any())
        } throws NonSuccessfulResponseCodeException(428, "proof required")

        val job = job(screenshotMessage(For.Account(peerId)))

        assertFailsWith<NonSuccessfulResponseCodeException> { job.onPushSend() }

        verify(exactly = 0) { rowWriter.withdraw(any()) }
    }

    @Test
    fun `5xx rejection still propagates so the job retries`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any(), any())
        } throws NonSuccessfulResponseCodeException(503, "service unavailable")

        val job = job(screenshotMessage(For.Account(peerId)))

        assertFailsWith<NonSuccessfulResponseCodeException> { job.onPushSend() }

        verify(exactly = 0) { rowWriter.withdraw(any()) }
    }

    @Test
    fun `markSent failure is swallowed — the message was already sent`() = runTest {
        every { rowWriter.markSent(any()) } throws RuntimeException("simulated DB write failure")

        val job = job(screenshotMessage(For.Account(peerId)))

        
        job.onPushSend()

        coVerify(exactly = 1) { messageSender.sendDataMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onFailure withdraws the row`() {
        val original = screenshotMessage(For.Account(peerId))

        job(original).onFailure()

        verify(exactly = 1) { rowWriter.withdraw(match { it.id == original.id }) }
    }

    @Test
    fun `onFailure withdraw failure is swallowed`() {
        every { rowWriter.withdraw(any()) } throws RuntimeException("simulated DB delete failure")

        job(screenshotMessage(For.Account(peerId))).onFailure()
    }

    @Test
    fun `serialize roundtrip preserves timestamp, target and screenShot`() {
        val job = job(screenshotMessage(For.Group(groupId)))

        val restored = gson.fromJson(
            job.serialize().getString("message_out"),
            TextMessage::class.java
        )

        assertEquals(timeStamp, restored.timeStamp)
        assertEquals(For.Group(groupId), restored.forWhat)
        assertEquals(screenshotText, restored.text)
        assertEquals(timeStamp, restored.screenShot?.realSource?.timestamp)
        assertEquals(DEFAULT_DEVICE_ID, restored.screenShot?.realSource?.sourceDevice)
    }

    @Test
    fun `parameters retry unbounded within a one-day lifespan and require network`() {
        val parameters = job(screenshotMessage(For.Account(peerId))).parameters

        
        
        assertEquals(Job.Parameters.UNLIMITED, parameters.maxAttempts)
        assertEquals(24 * 60 * 60 * 1000L, parameters.lifespan)
        assertTrue(parameters.constraintKeys.contains(NetworkConstraint.KEY))
    }

    private fun job(textMessage: TextMessage) = PushScreenShotSendJob(
        parameters = null,
        textMessage = textMessage,
        messageSender = messageSender,
        dataMessageCreator = dataMessageCreator,
        rowWriter = rowWriter,
        gson = gson,
        messagingGate = TestMessagingPause().open().gate,
        groupUtil = groupUtil,
    ).bindLogin()

    private fun screenshotMessage(forWhat: For) = TextMessage(
        LocalMessageCreator.generateMessageId(timeStamp, myId, DEFAULT_DEVICE_ID),
        For.Account(myId),
        forWhat,
        timeStamp,
        timeStamp,
        timeStamp,
        SendType.Sending.rawValue,
        0,
        0,
        0,
        0,
        screenshotText,
        screenShot = ScreenShot(RealSource(myId, DEFAULT_DEVICE_ID, timeStamp, timeStamp)),
    )

    private fun successResult(): SendMessageResult = SendMessageResult.success(
        address = "addr",
        needsSync = false,
        duration = 0L,
        systemShowTimestamp = serverSystemShowTimestamp,
        notifySequenceId = serverNotifySequenceId,
        sequenceId = serverSequenceId
    )
}
