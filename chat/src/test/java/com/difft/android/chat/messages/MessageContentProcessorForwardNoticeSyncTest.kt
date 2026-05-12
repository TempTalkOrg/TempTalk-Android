package com.difft.android.chat.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import difft.android.messageserialization.For
import difft.android.messageserialization.model.NotifyMessage
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ForwardNoticeMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage

/**
 * Integration tests for the 1v1-to-other self-sync path of the forward-notice
 * receive pipeline: `Content.syncMessage.forwardNoticeSync` branch of
 * [MessageContentProcessor.handleMessage].
 *
 * Split out of [MessageContentProcessorForwardNoticeTest] to keep each file
 * under the 500-line project limit. Primary-path (`Content.forwardNotice`)
 * tests live in the sibling file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageContentProcessorForwardNoticeSyncTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Application
    private lateinit var processor: MessageContentProcessor
    private lateinit var localMessageCreator: LocalMessageCreator
    private lateinit var globalServicesMock: GlobalHiltEntryPoint

    private val fakeNotifyResult: NotifyMessage = mockk(relaxed = true)

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        context = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(testDispatcher)

        globalServicesMock = mockk(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns MY_ID

        localMessageCreator = mockk(relaxed = true)
        coEvery {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        } returns fakeNotifyResult

        processor = MessageContentProcessor(
            context = context,
            dbRoomStore = mockk(relaxed = true),
            messageStore = mockk(relaxed = true),
            asyncMessageJobsManager = mockk(relaxed = true),
            contactsUpdater = mockk(relaxed = true),
            groupUpdater = mockk(relaxed = true),
            messageArchiveManager = mockk(relaxed = true),
            lCallManagerProvider = mockk(relaxed = true),
            receiptMessageHelper = mockk(relaxed = true),
            messageNotificationUtil = mockk(relaxed = true),
            conversationSettingsManager = mockk(relaxed = true),
            localMessageCreator = localMessageCreator,
            groupCryptoRepo = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ------------------------------------------------------------------
    // Self-sync 1v1 via SyncMessage.forwardNoticeSync — payload.number = peer's uid.
    // Operator = myId; conversation = For.Account(peer from payload).
    // ------------------------------------------------------------------
    @Test
    fun `self-sync 1v1 via SyncMessage resolves to payload number and operator is myId`() = runTest {
        val peer = "+15554444"
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.COMBINED)
            .addSourceAuthorIds("+16001")
            .setMessageCount(2)
            .setConversation(ConversationId.newBuilder().setNumber(peer).build())
            .build()
        val envelope = minimalEnvelope(MY_ID, 200L)
        val content = Content.newBuilder()
            .setSyncMessage(SyncMessage.newBuilder().setForwardNoticeSync(forwardNotice).build())
            .build()

        processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        val operatorSlot = slot<String>()
        val forWhatSlot = slot<For>()
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(
                operatorId = capture(operatorSlot),
                forWhat = capture(forWhatSlot),
                noticeData = any(),
                systemShowTimestamp = any(),
                timestamp = any(),
                sourceDevice = any()
            )
        }
        assertEquals("operator on self-sync is me", MY_ID, operatorSlot.captured)
        assertEquals(For.Account(peer), forWhatSlot.captured)
    }

    // ------------------------------------------------------------------
    // Self-sync with malformed/missing conversation — must drop silently (return
    // null + warn log), NOT route into Note-to-Self via the upstream lazy's
    // `For.Account(senderId)` fallback. Guards against silent data corruption
    // should a malformed payload ever reach this device.
    // ------------------------------------------------------------------
    @Test
    fun `self-sync drops silently when payload conversation is missing`() = runTest {
        // conversation field entirely absent
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.SINGLE)
            .addSourceAuthorIds("+16001")
            .setMessageCount(1)
            .build()
        val envelope = minimalEnvelope(MY_ID, 400L)
        val content = Content.newBuilder()
            .setSyncMessage(SyncMessage.newBuilder().setForwardNoticeSync(forwardNotice).build())
            .build()

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG)
        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `self-sync drops silently when payload conversation number is empty`() = runTest {
        // conversation present but .number is the empty proto2 default (no groupId either)
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.SINGLE)
            .addSourceAuthorIds("+16001")
            .setMessageCount(1)
            .setConversation(ConversationId.newBuilder().build())
            .build()
        val envelope = minimalEnvelope(MY_ID, 500L)
        val content = Content.newBuilder()
            .setSyncMessage(SyncMessage.newBuilder().setForwardNoticeSync(forwardNotice).build())
            .build()

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG)
        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // -------- helpers --------
    private fun minimalEnvelope(source: String, ts: Long): Envelope = Envelope.newBuilder()
        .setSource(source)
        .setSourceDevice(1)
        .setTimestamp(ts)
        .setSystemShowTimestamp(ts)
        .build()

    companion object {
        private const val MY_ID = "+10000000"
        private const val TAG = "TestTag"
    }
}
