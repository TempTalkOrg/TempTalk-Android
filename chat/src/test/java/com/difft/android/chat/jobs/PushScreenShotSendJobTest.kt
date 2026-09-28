package com.difft.android.chat.jobs

import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.chat.common.SendType
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.chat.util.DataMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.messages.SendMessageResult
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.RateLimitException
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.Message
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

/**
 * Unit tests for [PushScreenShotSendJob.onPushSend] — the send-first, persist-on-success contract
 * that keeps an offline screenshot notice from leaving an unclearable "Send failed" tag (#1265).
 *
 * Coverage:
 *  1. Server accepted → exactly one row persisted: the job's own TextMessage, Sent, carrying the
 *     server's ordering keys and still holding its `screenShot` (the unified representation every
 *     device stores for this event).
 *  2. Server rejected (`result.success == null`) → nothing persisted, no throw (the job simply ends).
 *  3. Sender throws IOException → propagated (PushSendJob retries), nothing persisted.
 *  4. Sender throws a permanent-code `NonSuccessfulResponseCodeException` (404/430/432, the same
 *     set PushTextSendJob gives up on) → swallowed, nothing persisted, job leaves the queue.
 *     Retryable 4xx (429 rate limit, 428 proof required) and 5xx still propagate so the retry
 *     and Retry-After backoff policy applies.
 *  5. `putWhenNonExist` throws → swallowed; the sender is NOT called again (a retry would duplicate
 *     the envelope for peers that already have it).
 *  6. `serialize()` round-trips the TextMessage (timestamp / target / screenShot).
 *  7. Job parameters: unbounded attempts inside a one-day lifespan, gated on network.
 *
 * Robolectric because the shared notification builder reaches `android.util.Base64` on the group
 * path. No Hilt: the job takes all of its collaborators through the constructor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushScreenShotSendJobTest {

    private lateinit var messageSender: NewSignalServiceMessageSender
    private lateinit var dataMessageCreator: DataMessageCreator
    private lateinit var messageStore: MessageStore

    /** Everything the job handed to [MessageStore.putWhenNonExist], in call order. */
    private val persisted = mutableListOf<Message>()

    // Production Gson wiring — For.Account/For.Group must round-trip through serialize().
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

    // Deliberately different from timeStamp: the row must carry the SERVER's keys, not the local ones.
    private val serverSystemShowTimestamp = timeStamp + 137L
    private val serverNotifySequenceId = 42L
    private val serverSequenceId = 99L

    @Before
    fun setUp() {
        persisted.clear()
        messageSender = mockk()
        dataMessageCreator = mockk()
        messageStore = mockk(relaxed = true)

        // Record what actually reached the store, so the assertions read the row's real field
        // values rather than a matcher's view of them.
        every { messageStore.putWhenNonExist(capture(persisted)) } just Runs

        every { dataMessageCreator.createFrom(any()) } returns
            SignalServiceProtos.DataMessage.newBuilder().setTimestamp(timeStamp).build()
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any())
        } returns successResult()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `server accepted — persists the same screenshot row every device stores`() = runTest {
        val original = screenshotMessage(For.Account(peerId))
        val job = job(original)

        job.onPushSend()

        val stored = persisted.single() as TextMessage
        // Same row identity the send went out with — and the same id this device's own sync echo
        // carries, which is what makes putWhenNonExist keep exactly one row.
        assertEquals(original.id, stored.id)
        assertEquals(timeStamp, stored.timeStamp)
        assertEquals(For.Account(peerId), stored.forWhat)
        // The row is only ever written as Sent, which is why neither room aggregate needs a write.
        assertEquals(SendType.Sent.rawValue, stored.sendType)
        // The server's own ordering keys, exactly as PushTextSendJob.updateMessage(Sent) recorded.
        assertEquals(serverSystemShowTimestamp, stored.systemShowTimestamp)
        assertEquals(serverNotifySequenceId, stored.notifySequenceId)
        assertEquals(serverSequenceId, stored.sequenceId)
        // TYPE_TEXT + screenShot is the unified representation (PR #226) rendered via
        // VIEW_TYPE_SCREENSHOT — the peer and my other devices hold the same shape.
        assertNotNull(stored.screenShot)
        assertEquals(screenshotText, stored.text)
    }

    @Test
    fun `group target also persists the row`() = runTest {
        val job = job(screenshotMessage(For.Group(groupId)))

        job.onPushSend()

        val stored = persisted.single() as TextMessage
        assertEquals(For.Group(groupId), stored.forWhat)
        assertEquals(SendType.Sent.rawValue, stored.sendType)
    }

    @Test
    fun `server rejected — nothing persisted and no throw`() = runTest {
        val rejected = mockk<SendMessageResult>()
        every { rejected.success } returns null
        coEvery { messageSender.sendDataMessage(any(), any(), any(), any()) } returns rejected

        val job = job(screenshotMessage(For.Account(peerId)))

        // Must not throw: there is no row to mark failed and nothing a retry could fix.
        job.onPushSend()

        verify(exactly = 0) { messageStore.putWhenNonExist(any()) }
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `sender IOException propagates and nothing is persisted`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any())
        } throws IOException("simulated network failure")

        val job = job(screenshotMessage(For.Account(peerId)))

        assertFailsWith<IOException> { job.onPushSend() }

        // The peer never got the message, so this device must not render a row for it either.
        verify(exactly = 0) { messageStore.putWhenNonExist(any()) }
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `432 rejection is permanent — no throw and nothing persisted`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any())
        } throws NonSuccessfulResponseCodeException(432, "non-friend daily limit")

        val job = job(screenshotMessage(For.Account(peerId)))

        // NonSuccessfulResponseCodeException IS an IOException, so without the permanent branch
        // onShouldRetry would retry this final answer for the whole one-day lifespan.
        job.onPushSend()

        verify(exactly = 0) { messageStore.putWhenNonExist(any()) }
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `rate limit 429 still propagates so the job retries`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any())
        } throws RateLimitException(429, "rate limit exceeded")

        val job = job(screenshotMessage(For.Account(peerId)))

        // A retryable 4xx must reach PushSendJob's retry/backoff policy, not the permanent branch.
        assertFailsWith<RateLimitException> { job.onPushSend() }

        verify(exactly = 0) { messageStore.putWhenNonExist(any()) }
    }

    @Test
    fun `proof required 428 still propagates so the Retry-After backoff applies`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any())
        } throws NonSuccessfulResponseCodeException(428, "proof required")

        val job = job(screenshotMessage(For.Account(peerId)))

        assertFailsWith<NonSuccessfulResponseCodeException> { job.onPushSend() }

        verify(exactly = 0) { messageStore.putWhenNonExist(any()) }
    }

    @Test
    fun `5xx rejection still propagates so the job retries`() = runTest {
        coEvery {
            messageSender.sendDataMessage(any(), any(), any(), any())
        } throws NonSuccessfulResponseCodeException(503, "service unavailable")

        val job = job(screenshotMessage(For.Account(peerId)))

        assertFailsWith<NonSuccessfulResponseCodeException> { job.onPushSend() }

        verify(exactly = 0) { messageStore.putWhenNonExist(any()) }
    }

    @Test
    fun `persist failure is swallowed — the message was already sent`() = runTest {
        every { messageStore.putWhenNonExist(any()) } throws
            RuntimeException("simulated DB write failure")

        val job = job(screenshotMessage(For.Account(peerId)))

        // A throw here would reach PushSendJob.onRun() → retry → duplicate envelope for the peer.
        job.onPushSend()

        coVerify(exactly = 1) { messageSender.sendDataMessage(any(), any(), any(), any()) }
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

        // The notice has no resend UI, so a spent attempt budget would drop it silently. The
        // network constraint is what keeps attempts from being burned while simply offline.
        assertEquals(Job.Parameters.UNLIMITED, parameters.maxAttempts)
        assertEquals(24 * 60 * 60 * 1000L, parameters.lifespan)
        assertTrue(parameters.constraintKeys.contains(NetworkConstraint.KEY))
    }

    // ----- helpers -----

    private fun job(textMessage: TextMessage) = PushScreenShotSendJob(
        parameters = null,
        textMessage = textMessage,
        messageSender = messageSender,
        dataMessageCreator = dataMessageCreator,
        messageStore = messageStore,
        gson = gson,
    )

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
