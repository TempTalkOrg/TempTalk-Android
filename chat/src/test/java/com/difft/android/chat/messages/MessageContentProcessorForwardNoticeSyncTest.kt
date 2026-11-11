package com.difft.android.chat.messages

import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.test.fakes.TestMessagingPause
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.google.gson.Gson
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










@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageContentProcessorForwardNoticeSyncTest {

    
    private val admission = requireNotNull(TestMessagingPause().open().gate.tryAdmit(AdmissionKind.RECEIVE, "test"))

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
            settingsApplier = mockk(relaxed = true),
            localMessageCreator = localMessageCreator,
            groupCryptoRepo = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
            weakContactReconciler = mockk(relaxed = true),
            gson = Gson(),
            conversationUnreadMarker = mockk(relaxed = true),
            criticalAlertHighlightWriter = mockk(relaxed = true),
            groupFocusRepository = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    
    
    
    
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

        processor.process(SignalServiceDataClass(envelope, content, null), TAG, admission)

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

    
    
    
    
    
    
    @Test
    fun `self-sync drops silently when payload conversation is missing`() = runTest {
        
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.SINGLE)
            .addSourceAuthorIds("+16001")
            .setMessageCount(1)
            .build()
        val envelope = minimalEnvelope(MY_ID, 400L)
        val content = Content.newBuilder()
            .setSyncMessage(SyncMessage.newBuilder().setForwardNoticeSync(forwardNotice).build())
            .build()

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG, admission)
        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `self-sync drops silently when payload conversation number is empty`() = runTest {
        
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

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG, admission)
        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    
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
