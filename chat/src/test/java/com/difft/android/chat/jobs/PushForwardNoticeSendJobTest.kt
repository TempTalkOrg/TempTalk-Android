package com.difft.android.chat.jobs

import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.messages.SendMessageResult
import com.google.gson.Gson
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.NotifyMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith










@OptIn(ExperimentalCoroutinesApi::class)
class PushForwardNoticeSendJobTest {

    
    private val messagingGate = TestMessagingPause().open().gate


    private lateinit var messageSender: NewSignalServiceMessageSender
    private lateinit var localMessageCreator: LocalMessageCreator
    private val gson = Gson()

    private lateinit var mockGlobalServices: GlobalHiltEntryPoint

    private val peerId = "+peer"
    private val groupId = "group-abc"
    private val myId = "MY_ID"

    @Before
    fun setUp() {
        messageSender = mockk()
        localMessageCreator = mockk(relaxed = true)

        
        mockGlobalServices = mockk(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobalServices
        every { mockGlobalServices.myId } returns myId

        
        coEvery {
            messageSender.sendForwardNoticeMessage(
                recipient = any(),
                room = any(),
                message = any(),
                sendSyncToSelf = any(),
                sendTimestamp = any(),
                admission = any()
            )
        } returns successResult()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `group target — sendSyncToSelf is false and sender called once`() = runTest {
        val target = For.Group(groupId)
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.ONE_BY_ONE,
            listOf("+author-a", "+author-b"),
            3
        )
        val job = PushForwardNoticeSendJob(
            parameters = null,
            target = target,
            noticeData = notice,
            messageSender = messageSender,
            localMessageCreator = localMessageCreator,
            gson = gson,
            messagingGate = messagingGate
        ).bindLogin()

        job.onPushSend()

        coVerify(exactly = 1) {
            messageSender.sendForwardNoticeMessage(
                recipient = target,
                room = target,
                message = any(),
                sendSyncToSelf = false,
                sendTimestamp = any(),
                admission = any()
            )
        }
        
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(
                operatorId = myId,
                forWhat = target,
                noticeData = notice,
                systemShowTimestamp = any(),
                timestamp = any(),
                sourceDevice = DEFAULT_DEVICE_ID
            )
        }
    }
    
    @Test
    fun `1v1 to other — sendSyncToSelf is true`() = runTest {
        val target = For.Account(peerId)
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.SINGLE,
            listOf("+author"),
            1
        )
        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson, messagingGate
        ).bindLogin()

        job.onPushSend()

        coVerify(exactly = 1) {
            messageSender.sendForwardNoticeMessage(
                recipient = target,
                room = target,
                message = any(),
                sendSyncToSelf = true,       
                sendTimestamp = any(),
                admission = any()
            )
        }
    }
    
    
    @Test
    fun `self target save-to-notes — sendSyncToSelf is false`() = runTest {
        val target = For.Account(myId)
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.SAVE_TO_NOTES,
            listOf("+author-x"),
            5
        )
        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson, messagingGate
        ).bindLogin()

        job.onPushSend()

        coVerify(exactly = 1) {
            messageSender.sendForwardNoticeMessage(
                recipient = target,
                room = target,
                message = any(),
                sendSyncToSelf = false,
                sendTimestamp = any(),
                admission = any()
            )
        }
    }
    
    @Test
    fun `sender failure rethrows and local DB is not inserted`() = runTest {
        val target = For.Group(groupId)
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.COMBINED,
            listOf("+a"),
            1
        )
        coEvery {
            messageSender.sendForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        } throws IOException("simulated network failure")

        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson, messagingGate
        ).bindLogin()

        assertFailsWith<IOException> { job.onPushSend() }

        
        
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(
                any(), any(), any(), any(), any(), any()
            )
        }
    }
    
    
    @Test
    fun `local DB insert failure does not rethrow — peer already has the notice`() = runTest {
        val target = For.Account(peerId)
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.SINGLE,
            listOf("+author"),
            1
        )
        coEvery {
            localMessageCreator.createForwardNoticeMessage(
                any(), any(), any(), any(), any(), any()
            )
        } throws RuntimeException("simulated DB write failure")

        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson, messagingGate
        ).bindLogin()

        
        
        job.onPushSend()

        
        coVerify(exactly = 1) {
            messageSender.sendForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }
    
    
    
    
    @Test
    fun `timestamp flows unchanged from Job to Sender and LocalMessageCreator`() = runTest {
        val target = For.Group(groupId)
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.COMBINED,
            listOf("+author-a"),
            2
        )

        val senderTsCapture = slot<Long>()
        val creatorTsCapture = slot<Long>()
        coEvery {
            messageSender.sendForwardNoticeMessage(
                any(), any(), any(), any(), capture(senderTsCapture), any()
            )
        } returns successResult()
        coEvery {
            localMessageCreator.createForwardNoticeMessage(
                any(), any(), any(), any(), capture(creatorTsCapture), any()
            )
        } returns mockk<NotifyMessage>(relaxed = true)

        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson, messagingGate
        ).bindLogin()

        job.onPushSend()

        
        assertEquals(
            "Envelope.timestamp (sender) and NotifyMessage.timestamp (localDB) MUST match — " +
                "they form the messageId three-tuple alongside myId + deviceId.",
            senderTsCapture.captured,
            creatorTsCapture.captured
        )
    }
    
    
    @Test
    fun `serialize roundtrip preserves target + notice`() {
        val target = For.Group("g-1")
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.SAVE_TO_NOTES,
            listOf("+a", "+b", "+c"),
            7
        )

        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson, messagingGate
        ).bindLogin()

        val data = job.serialize()
        assertEquals("g-1", data.getString("target_id"))
        assertEquals(true, data.getBooleanOrDefault("target_is_group", false))

        val reJson = data.getString("notice_data_json")!!
        val reNotice = gson.fromJson(reJson, ForwardNoticeData::class.java)
        assertEquals(notice, reNotice)
    }

    private fun successResult(): SendMessageResult = SendMessageResult.success(
        address = "addr",
        needsSync = false,
        duration = 0L,
        systemShowTimestamp = 1_700_000_000_000L,
        notifySequenceId = 0L,
        sequenceId = 0L
    )
}
