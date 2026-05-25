package com.difft.android.chat.jobs

import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.util.DataMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.messages.SendMessageResult
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException
import com.google.gson.Gson
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.RealSource
import difft.android.messageserialization.model.TextMessage
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [PushReactionSendJob].
 *
 * `onPushSend` and the inherited `onShouldRetry` are `protected`. We expose them through
 * [TestablePushReactionSendJob] (a thin test subclass) instead of reflection so the test
 * remains readable.
 */
class PushReactionSendJobTest {

    private val gson = Gson()
    private val newSignalServiceMessageSender = mockk<NewSignalServiceMessageSender>(relaxed = true)
    private val dataMessageCreator = mockk<DataMessageCreator>(relaxed = true)
    private val messageStore = mockk<MessageStore>(relaxed = true)

    private val recipient = For.Account("+12025550100")

    private fun buildReactionTextMessage(
        emoji: String = "👍",
        remove: Boolean = false,
        originTimestamp: Long = 1_700_000_000_000L,
        realSource: RealSource? = RealSource(
            source = "+12025550199",
            sourceDevice = 1,
            timestamp = 1_699_999_999_000L,
            serverTimestamp = 1_699_999_999_500L,
        ),
    ): TextMessage {
        val reaction = Reaction(
            emoji = emoji,
            uid = "self-uid",
            remove = remove,
            originTimestamp = originTimestamp,
            realSource = realSource,
        )
        return TextMessage(
            id = "msg-id-1",
            fromWho = For.Account("self-uid"),
            forWhat = recipient,
            systemShowTimestamp = originTimestamp,
            timeStamp = originTimestamp,
            receivedTimeStamp = originTimestamp,
            sendType = 0,
            expiresInSeconds = 0,
            notifySequenceId = 0,
            sequenceId = 0,
            mode = 0,
            text = "",
            reactions = mutableListOf(reaction),
        )
    }

    private fun newJob(
        textMessage: TextMessage = buildReactionTextMessage(),
        parameters: Job.Parameters? = null,
    ): TestablePushReactionSendJob = TestablePushReactionSendJob(
        parameters = parameters,
        textMessage = textMessage,
        gson = gson,
        newSignalServiceMessageSender = newSignalServiceMessageSender,
        dataMessageCreator = dataMessageCreator,
        messageStore = messageStore,
    )

    // --- 1) buildParameters ---

    @Test
    fun `buildParameters has reaction-scoped queue, UNLIMITED attempts, 7 day lifespan, NetworkConstraint`() {
        val job = newJob()
        val params = job.parameters

        val expectedQueue = "[${PushReactionSendJob.KEY}::${recipient.id}]"
        assertEquals(expectedQueue, params.queue)
        assertEquals(Job.Parameters.UNLIMITED, params.maxAttempts)
        assertEquals(TimeUnit.DAYS.toMillis(7), params.lifespan)
        assertTrue(
            params.constraintKeys.contains(NetworkConstraint.KEY),
            "constraintKeys ${params.constraintKeys} should contain ${NetworkConstraint.KEY}"
        )
    }

    // --- 2) onPushSend transparently sends the data message ---

    @Test
    fun `onPushSend builds DataMessage and forwards it via sendDataMessage with no notification`() = runTest {
        val textMessage = buildReactionTextMessage()
        val dataMessage = SignalServiceProtos.DataMessage.getDefaultInstance()
        every { dataMessageCreator.createFrom(textMessage) } returns dataMessage
        coEvery {
            newSignalServiceMessageSender.sendDataMessage(any(), any(), any(), any())
        } returns mockk<SendMessageResult>(relaxed = true)

        val job = newJob(textMessage)
        job.invokeOnPushSend()

        coVerify(exactly = 1) {
            newSignalServiceMessageSender.sendDataMessage(
                recipient = textMessage.forWhat,
                room = textMessage.forWhat,
                message = dataMessage,
                notification = null,
            )
        }
    }

    // --- 3) onFailure rolls back via LWW reverse-write (add → remove) ---

    /**
     * Rollback timestamp is exactly `original.originTimestamp + 1`. This narrow +1 lets the
     * rollback beat the original optimistic write in DB LWW (which carries
     * `original.originTimestamp`), while intentionally LOSING to any newer user action whose
     * `originTimestamp` is strictly greater than `original+1` (typical: a newer optimistic
     * write driven by [com.difft.android.chat.jobs.ReactionSendCoordinator] supersede).
     * The previous formula `maxOf(now, original+1)` could overwrite a legitimately newer
     * optimistic update, silently dropping the user's reaction.
     */
    @Test
    fun `onFailure writes a rollback Reaction with flipped remove flag and originTimestamp+1`() {
        val originalTs = 1_700_000_000_000L
        val textMessage = buildReactionTextMessage(emoji = "❤", remove = false, originTimestamp = originalTs)
        val original = textMessage.reactions!!.first()

        val capturedReaction = slot<Reaction>()
        every {
            messageStore.updateMessageReaction(
                conversationId = recipient.id,
                reaction = capture(capturedReaction),
                reactionMessageId = null,
                envelopeBytes = null,
            )
        } just Runs

        val job = newJob(textMessage)
        job.onFailure()

        // Rollback is synchronous now — plain verify (not coVerify with timeout).
        verify(exactly = 1) {
            messageStore.updateMessageReaction(
                conversationId = recipient.id,
                reaction = any(),
                reactionMessageId = null,
                envelopeBytes = null,
            )
        }

        val rollback = capturedReaction.captured
        assertEquals(original.emoji, rollback.emoji)
        assertEquals(original.uid, rollback.uid)
        assertEquals(original.realSource, rollback.realSource)
        assertEquals(!original.remove, rollback.remove, "remove flag should be flipped")
        assertEquals(
            original.originTimestamp + 1,
            rollback.originTimestamp,
            "rollback originTimestamp must be exactly original+1 so it loses to newer optimistic updates",
        )
    }

    // --- 3b) onFailure rolls back the opposite direction (remove → add) ---

    @Test
    fun `onFailure rollback for remove=true writes a Reaction with remove=false (re-insert) and originTimestamp+1`() {
        // Original action was "remove the reaction"; that send permanently failed.
        // Rollback must re-insert the reaction by writing remove=false at original+1.
        val originalTs = 1_700_000_000_000L
        val textMessage = buildReactionTextMessage(
            emoji = "👎",
            remove = true,
            originTimestamp = originalTs,
        )
        val original = textMessage.reactions!!.first()

        val capturedReaction = slot<Reaction>()
        every {
            messageStore.updateMessageReaction(
                conversationId = recipient.id,
                reaction = capture(capturedReaction),
                reactionMessageId = null,
                envelopeBytes = null,
            )
        } just Runs

        val job = newJob(textMessage)
        job.onFailure()

        verify(exactly = 1) {
            messageStore.updateMessageReaction(
                conversationId = recipient.id,
                reaction = any(),
                reactionMessageId = null,
                envelopeBytes = null,
            )
        }

        val rollback = capturedReaction.captured
        assertEquals(original.emoji, rollback.emoji)
        assertEquals(original.uid, rollback.uid)
        assertEquals(original.realSource, rollback.realSource)
        assertFalse(rollback.remove, "rollback of remove=true must re-insert (remove=false)")
        // Rollback ts is exactly original+1: beats the original optimistic write, loses to any
        // newer user action (which would carry a strictly greater originTimestamp).
        assertEquals(
            original.originTimestamp + 1,
            rollback.originTimestamp,
            "rollback originTimestamp must be exactly original+1",
        )
    }

    // --- 4) onShouldRetry: IOException is retryable ---

    @Test
    fun `onShouldRetry returns true for IOException (inherited from PushSendJob)`() {
        val job = newJob()
        assertTrue(job.invokeOnShouldRetry(IOException("net")))
    }

    // --- 5) onShouldRetry: ServerRejectedException stops retry ---

    @Test
    fun `onShouldRetry returns false for ServerRejectedException (terminal failure)`() {
        val job = newJob()
        assertFalse(job.invokeOnShouldRetry(ServerRejectedException()))
    }

    /**
     * NonSuccessfulResponseCodeException extends IOException, so PushSendJob.onShouldRetry returns
     * true for it. The reaction job relies on the 7-day lifespan (not onShouldRetry) to bound retry
     * of server-side 4xx errors that aren't ServerRejectedException — exhausted lifespan triggers
     * onFailure and the LWW rollback. This test pins that behavior so future refactors don't
     * accidentally short-circuit legitimate retryable responses.
     */
    @Test
    fun `onShouldRetry returns true for NonSuccessfulResponseCodeException (relies on lifespan to bound retry)`() {
        val job = newJob()
        assertTrue(job.invokeOnShouldRetry(NonSuccessfulResponseCodeException(430, "blocked")))
    }

    // --- 6) onFailure with null reactions is a no-op ---

    @Test
    fun `onFailure does NOT call updateMessageReaction when reactions list is null`() {
        val textMessage = TextMessage(
            id = "msg-id-empty",
            fromWho = For.Account("self-uid"),
            forWhat = recipient,
            systemShowTimestamp = 0L,
            timeStamp = 0L,
            receivedTimeStamp = 0L,
            sendType = 0,
            expiresInSeconds = 0,
            notifySequenceId = 0,
            sequenceId = 0,
            mode = 0,
            text = "",
            reactions = null,
        )
        val job = newJob(textMessage)

        job.onFailure()

        verify(exactly = 0) {
            messageStore.updateMessageReaction(any(), any(), any(), any())
        }
    }

    // --- 6b) onFailure with empty reactions list is a no-op (sibling of #6) ---

    @Test
    fun `onFailure does NOT call updateMessageReaction when reactions list is empty`() {
        val textMessage = TextMessage(
            id = "msg-id-empty-list",
            fromWho = For.Account("self-uid"),
            forWhat = recipient,
            systemShowTimestamp = 0L,
            timeStamp = 0L,
            receivedTimeStamp = 0L,
            sendType = 0,
            expiresInSeconds = 0,
            notifySequenceId = 0,
            sequenceId = 0,
            mode = 0,
            text = "",
            reactions = mutableListOf(),
        )
        val job = newJob(textMessage)

        job.onFailure()

        verify(exactly = 0) {
            messageStore.updateMessageReaction(any(), any(), any(), any())
        }
    }

    /**
     * Test-only subclass that exposes the protected `onShouldRetry` and `onPushSend` of the parent.
     */
    private class TestablePushReactionSendJob(
        parameters: Job.Parameters?,
        textMessage: TextMessage,
        gson: Gson,
        newSignalServiceMessageSender: NewSignalServiceMessageSender,
        dataMessageCreator: DataMessageCreator,
        messageStore: MessageStore,
    ) : PushReactionSendJob(
        parameters,
        textMessage,
        gson,
        newSignalServiceMessageSender,
        dataMessageCreator,
        messageStore,
    ) {
        fun invokeOnShouldRetry(e: Exception): Boolean = onShouldRetry(e)
        suspend fun invokeOnPushSend() = onPushSend()
    }
}
