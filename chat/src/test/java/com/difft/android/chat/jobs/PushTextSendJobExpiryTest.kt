package com.difft.android.chat.jobs

import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.util.DataMessageCreator
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.messages.SendMessageResult
import com.google.gson.Gson
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.TextMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.io.IOException
import kotlin.test.assertFailsWith


class PushTextSendJobExpiryTest {
    private val sender = mockk<NewSignalServiceMessageSender>()
    private val creator = mockk<DataMessageCreator> {
        every { createFrom(any()) } returns SignalServiceProtos.DataMessage.getDefaultInstance()
    }
    private val store = mockk<MessageStore> {
        every { putWhenNonExist(any()) } throws IOException("row-store boundary")
    }
    private val groupUtil = mockk<GroupUtil>(relaxed = true)

    @Test
    fun `server acceptance clears group expiry before local status persistence`() = runTest {
        accepted()
        assertFailsWith<IOException> { job(message(For.Group("group"))).onPushSend() }
        coVerify(exactly = 1) { groupUtil.clearExpiry("group", "sent") }
    }

    @Test
    fun `a transport failure never clears expiry`() = runTest {
        coEvery { sender.sendDataMessage(any(), any(), any(), any(), any()) } throws IOException("offline")
        assertFailsWith<IOException> { job(message(For.Group("group"))).onPushSend() }
        coVerify(exactly = 0) { groupUtil.clearExpiry(any(), any()) }
    }

    @Test
    fun `successful direct send does not clear a group`() = runTest {
        accepted()
        assertFailsWith<IOException> { job(message(For.Account("peer"))).onPushSend() }
        coVerify(exactly = 0) { groupUtil.clearExpiry(any(), any()) }
    }

    @Test
    fun `legacy reaction-only send is excluded`() = runTest {
        accepted()
        val reaction = message(For.Group("group")).apply { reactions = listOf(mockk()) }
        job(reaction).onPushSend()
        coVerify(exactly = 0) { groupUtil.clearExpiry(any(), any()) }
    }

    private fun accepted() {
        coEvery { sender.sendDataMessage(any(), any(), any(), any(), any()) } returns SendMessageResult.success(
            address = "group", needsSync = false, duration = 0,
            systemShowTimestamp = 2, notifySequenceId = 3, sequenceId = 4,
        )
    }

    private fun job(message: TextMessage) = PushTextSendJob(
        parameters = null,
        textMessage = message,
        notification = mockk(relaxed = true),
        gson = Gson(),
        newSignalServiceMessageSender = sender,
        dataMessageCreator = creator,
        localMessageCreator = mockk(relaxed = true),
        messageStore = store,
        groupUtil = groupUtil,
        messagingGate = TestMessagingPause().open().gate,
    ).bindLogin()

    private fun message(target: For) = TextMessage(
        id = "message", fromWho = For.Account("me"), forWhat = target,
        systemShowTimestamp = 1, timeStamp = 1, receivedTimeStamp = 1,
        sendType = 0, expiresInSeconds = 0, notifySequenceId = 0, sequenceId = 0, mode = 0, text = "text",
    )
}
