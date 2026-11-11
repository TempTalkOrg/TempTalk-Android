package com.difft.android.chat.messages

import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.test.fakes.TestMessagingPause
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.PendingMessageHelper
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.network.responses.PendingMessage
import com.difft.android.websocket.api.messages.ConversationPreviewWrapper
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.util.copyWithMsgExtraConversationId
import com.google.gson.Gson
import com.google.protobuf.ByteString
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.NotifyMessage
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.difft.app.database.isGroupMember
import org.difft.app.database.wcdb
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationPreview
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CopyData
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageActivityNotice
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MsgExtra
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.RealSource
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage







@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageContentProcessorConversationCrossCheckTest {

    
    private val admission = requireNotNull(TestMessagingPause().open().gate.tryAdmit(AdmissionKind.RECEIVE, "test"))

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Application
    private lateinit var processor: MessageContentProcessor
    private lateinit var asyncMessageJobsManager: AsyncMessageJobsManager
    private lateinit var localMessageCreator: LocalMessageCreator
    private lateinit var globalServicesMock: GlobalHiltEntryPoint

    
    
    private lateinit var decryptionUtil: NewMessageDecryptionUtil
    private lateinit var envelopeDbMessageStore: DBMessageStore
    private lateinit var envelopToMessageProcessor: EnvelopToMessageProcessor
    private lateinit var incomingConversationProcessor: IncomingConversationMessageProcessor

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

        
        
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")

        asyncMessageJobsManager = mockk(relaxed = true)
        localMessageCreator = mockk(relaxed = true)
        coEvery {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        } returns fakeNotifyResult
        coEvery {
            localMessageCreator.createActivityNoticeMessage(any(), any(), any(), any(), any(), any())
        } returns fakeNotifyResult

        processor = MessageContentProcessor(
            context = context,
            dbRoomStore = mockk(relaxed = true),
            messageStore = mockk(relaxed = true),
            asyncMessageJobsManager = asyncMessageJobsManager,
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

        
        decryptionUtil = mockk(relaxed = true)
        envelopeDbMessageStore = mockk(relaxed = true)
        envelopToMessageProcessor = EnvelopToMessageProcessor(
            newMessageDecryptionUtil = decryptionUtil,
            messageContentProcessor = processor,
            dbMessageStore = envelopeDbMessageStore,
            healthCounters = mockk(relaxed = true),
            lateDeliveryTracker = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
        )
        incomingConversationProcessor = IncomingConversationMessageProcessor(
            webSocket = mockk(relaxed = true),
            dbRoomStore = mockk(relaxed = true),
            envelopToMessageProcessor = envelopToMessageProcessor,
            priorConversationReporter = mockk(relaxed = true),
            messagingGate = TestMessagingPause().open().gate,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }
    

    
    
    @Test
    fun `IT-INJECT-1v1 group content via 1v1 envelope is rejected`() = runTest {
        val attacker = "+19990001"
        val victimGroup = "a".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        val content = groupData(victimGroup, body = "hi")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNull(result)
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }

    
    @Test
    fun `IT-INJECT content group differs from envelope group is rejected`() = runTest {
        val peer = "+15551111"
        val contentGroup = "a".repeat(32)
        val envelopeGroup = "b".repeat(32)
        val env = envelopeWith(source = peer, conv = For.Group(envelopeGroup))
        val content = groupData(contentGroup, body = "hi")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNull(result)
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }

    
    
    @Test
    fun `IT-INJECT 1v1 content via group envelope is rejected`() = runTest {
        val peer = "+15552222"
        val envelopeGroup = "c".repeat(32)
        val env = envelopeWith(source = peer, conv = For.Group(envelopeGroup))
        val content = oneToOneData()

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNull(result)
    }
    

    
    @Test
    fun `IT-PASS-GROUP legal group inner equals outer is processed`() = runTest {
        val peer = "+15553333"
        val group = "d".repeat(32)
        val env = envelopeWith(source = peer, conv = For.Group(group))
        val content = groupData(group, body = "hi")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
        assertNotNull(result)
    }

    
    @Test
    fun `IT-PASS-1v1 legal 1v1 with matching sender stamp is processed`() = runTest {
        val peer = "+15554444"
        val env = envelopeWith(source = peer, conv = For.Account(peer))
        val content = oneToOneData()

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNotNull(result)
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }
    

    @Test
    fun `IT-ABSENT-GROUP no envelope conversationId is processed`() = runTest {
        val peer = "+15555555"
        val group = "e".repeat(32)
        val env = envelopeWith(source = peer, conv = null)
        val content = groupData(group, body = "hi")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
        assertNotNull(result)
    }

    @Test
    fun `IT-ABSENT-1v1 no envelope conversationId is processed`() = runTest {
        val peer = "+15556666"
        val env = envelopeWith(source = peer, conv = null)
        val content = oneToOneData()

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNotNull(result)
    }
    
    

    
    
    @Test
    fun `IT-SELF-GROUP self-sent group is processed despite mismatch`() = runTest {
        val group = "f".repeat(32)
        val env = envelopeWith(source = MY_ID, conv = For.Account("+10009999"))
        val content = groupData(group, body = "")

        processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
    }

    
    
    @Test
    fun `IT-SYNC-NOTCHECK sync-sent group bypasses the mount`() = runTest {
        val group = "g".repeat(32)
        val env = envelopeWith(source = MY_ID, conv = For.Account("+10009999"))
        val content = syncSentGroup(group)

        processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
    }
    
    

    
    
    @Test
    fun `IT-RECALL-REJECT recall via wrong channel is rejected at mount`() = runTest {
        val attacker = "+19990003"
        val victimGroup = "h".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        val content = groupData(victimGroup, recallSource = "+18880001")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNull(result)
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }

    
    
    
    @Test
    fun `IT-RECALL-AUTH legal channel wrong source still dropped by source gate`() = runTest {
        val member = "+15557777"
        val group = "i".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = groupData(group, recallSource = "+18880002") 

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
        assertNull(result)
    }
    

    
    
    @Test
    fun `IT-C3-BUILDENV buildEnvelope round-trips identically to content side`() = runTest {
        val peer = "+15558888"
        val group = "j".repeat(32)

        val helper = PendingMessageHelper(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val pending = PendingMessage(
            type = 1, relay = null, timestamp = 100L, source = peer, sourceDevice = 1,
            content = null, systemShowTimestamp = 100L, sequenceId = 0L, notifySequenceId = 0L,
            msgType = 1, conversation = group, identityKey = null, peerContext = null,
        )
        val method = PendingMessageHelper::class.java
            .getDeclaredMethod("buildEnvelope", PendingMessage::class.java)
            .apply { isAccessible = true }
        val built = method.invoke(helper, pending) as Envelope

        
        val c2Bytes = envelopeWith(peer, null)
            .copyWithMsgExtraConversationId(For.Group(group))
            .msgExtra.conversationId.groupId.toByteArray()
        assertArrayEquals(c2Bytes, built.msgExtra.conversationId.groupId.toByteArray())

        
        val result = processor.process(SignalServiceDataClass(built, groupData(group, body = "hi"), null), TAG, admission)
        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
        assertNotNull(result)
    }
    
    

    
    
    @Test
    fun `FWD-INJECT group forward via 1v1 envelope is rejected`() = runTest {
        val attacker = "+19990101"
        val group = "p".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    
    @Test
    fun `FWD-PASS legal group forward inner equals outer is processed`() = runTest {
        val member = "+15551111"
        val group = "q".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    
    @Test
    fun `FWD-ABSENT no envelope conversationId is processed`() = runTest {
        val peer = "+15551212"
        val group = "r".repeat(32)
        val env = envelopeWith(source = peer, conv = null)
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    
    @Test
    fun `FWD-REGRESS-REMOVED-GUARD legit forward renders without roster guard`() = runTest {
        val member = "+15551313"
        val group = "s".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { wcdb.isGroupMember(any(), any()) }
    }

    
    
    
    @Test
    fun `FWD-INJECT content group differs from envelope group is rejected`() = runTest {
        val member = "+15551414"
        val contentGroup = "x".repeat(32)
        val envelopeGroup = "y".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(envelopeGroup))
        val content = forwardNoticeGroup(contentGroup)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    
    
    
    @Test
    fun `FWD-SELF self-authored forward is processed despite mismatch`() = runTest {
        val group = "z".repeat(32)
        val env = envelopeWith(source = MY_ID, conv = For.Account("+10009999"))
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }
    
    

    
    @Test
    fun `ACT-INJECT group activity via 1v1 envelope is rejected`() = runTest {
        val attacker = "+19990202"
        val group = "t".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        val content = activityNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createActivityNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    
    @Test
    fun `ACT-PASS legal group activity inner equals outer is processed`() = runTest {
        val member = "+15552121"
        val group = "u".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = activityNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createActivityNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    
    @Test
    fun `ACT-ABSENT no envelope conversationId is processed`() = runTest {
        val peer = "+15552323"
        val group = "v".repeat(32)
        val env = envelopeWith(source = peer, conv = null)
        val content = activityNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createActivityNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    
    @Test
    fun `ACT-REGRESS-REMOVED-GUARD legit activity renders without roster guard`() = runTest {
        val member = "+15552424"
        val group = "w".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = activityNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG, admission)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createActivityNoticeMessage(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { wcdb.isGroupMember(any(), any()) }
    }
    
    
    
    

    
    
    
    @Test
    fun `IT-PREVIEW-GROUP conversation-preview group message is processed`() = runTest {
        val peer = "+15559991"
        val group = "n".repeat(32)
        every { decryptionUtil.decrypt(any()) } answers {
            SignalServiceDataClass(firstArg(), groupData(group, body = "hi"), null)
        }

        val preview = conversationPreview(source = peer, conv = For.Group(group))
        incomingConversationProcessor.income(ConversationPreviewWrapper(preview), REQUEST_ID)
        incomingConversationProcessor.endReceive(REQUEST_ID)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
    }

    
    
    @Test
    fun `IT-PREVIEW-SELF self-authored preview is not cross-checked`() = runTest {
        val group = "o".repeat(32)
        every { decryptionUtil.decrypt(any()) } answers {
            SignalServiceDataClass(firstArg(), groupData(group, body = ""), null)
        }

        
        val preview = conversationPreview(source = MY_ID, conv = For.Account("+10009999"))
        incomingConversationProcessor.income(ConversationPreviewWrapper(preview), REQUEST_ID)
        incomingConversationProcessor.endReceive(REQUEST_ID)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
    }
    
    

    @Test
    fun `IT-ACK REJECT resolves to Success null not a retry and is not persisted`() = runTest {
        val attacker = "+19990009"
        val victimGroup = "m".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        every { decryptionUtil.decrypt(any()) } returns
            SignalServiceDataClass(env, groupData(victimGroup, body = "hi"), null)

        val res = envelopToMessageProcessor.process(
            env, TAG, requireNotNull(TestMessagingPause().open().gate.tryAdmit(AdmissionKind.RECEIVE, TAG)),
        )

        
        assertTrue(res is EnvelopeProcessResult.Success)
        assertNull((res as EnvelopeProcessResult.Success).result)
        
        verify(exactly = 0) { envelopeDbMessageStore.putWhenNonExist(any<Message>()) }
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }

    
    private fun envelopeWith(source: String, conv: For?, ts: Long = 100L): Envelope {
        val b = Envelope.newBuilder()
            .setSource(source).setSourceDevice(1).setTimestamp(ts).setSystemShowTimestamp(ts)
        if (conv != null) {
            val cid = ConversationId.newBuilder()
            if (conv is For.Group) cid.groupId = ByteString.copyFromUtf8(conv.id) else cid.number = conv.id
            b.msgExtra = MsgExtra.newBuilder().setConversationId(cid.build()).build()
        }
        return b.build()
    }

    



    private fun conversationPreview(source: String, conv: For, ts: Long = 100L): ConversationPreview {
        val cid = ConversationId.newBuilder()
        if (conv is For.Group) cid.groupId = ByteString.copyFromUtf8(conv.id) else cid.number = conv.id
        return ConversationPreview.newBuilder()
            .setConversationId(cid.build())
            .setLastestMsg(envelopeWith(source = source, conv = null, ts = ts))
            .build()
    }

    
    private fun groupData(gid: String, body: String = "", recallSource: String? = null): Content {
        val dm = DataMessage.newBuilder()
            .setGroup(
                DataMessage.Group.newBuilder()
                    .setId(ByteString.copyFromUtf8(gid))
                    .setType(DataMessage.Group.Type.DELIVER)
                    .build()
            )
        if (body.isNotEmpty()) dm.body = body
        if (recallSource != null) {
            dm.recall = DataMessage.Recall.newBuilder()
                .setSource(RealSource.newBuilder().setSource(recallSource).setTimestamp(1L).build())
                .build()
        }
        return Content.newBuilder().setDataMessage(dm.build()).build()
    }

    
    private fun oneToOneData(body: String = "hi"): Content =
        Content.newBuilder().setDataMessage(DataMessage.newBuilder().setBody(body).build()).build()

    
    private fun syncSentGroup(gid: String): Content {
        val dm = DataMessage.newBuilder()
            .setGroup(
                DataMessage.Group.newBuilder()
                    .setId(ByteString.copyFromUtf8(gid))
                    .setType(DataMessage.Group.Type.DELIVER)
                    .build()
            )
            .build()
        val sent = SyncMessage.Sent.newBuilder().setMessage(dm).setTimestamp(100L).build()
        return Content.newBuilder().setSyncMessage(SyncMessage.newBuilder().setSent(sent).build()).build()
    }

    
    private fun forwardNoticeGroup(gid: String): Content {
        val forwardNotice = org.whispersystems.signalservice.internal.push.SignalServiceProtos.ForwardNoticeMessage.newBuilder()
            .setScene(org.whispersystems.signalservice.internal.push.SignalServiceProtos.ForwardNoticeMessage.ForwardScene.COMBINED)
            .addSourceAuthorIds("+12001")
            .setMessageCount(1)
            .setConversation(ConversationId.newBuilder().setGroupId(ByteString.copyFromUtf8(gid)).build())
            .build()
        return Content.newBuilder().setForwardNotice(forwardNotice).build()
    }

    
    private fun activityNoticeGroup(gid: String): Content {
        val notice = MessageActivityNotice.newBuilder()
            .setCopyData(CopyData.newBuilder().addSourceAuthorIds("+a").setMessageCount(1).build())
            .setConversation(ConversationId.newBuilder().setGroupId(ByteString.copyFromUtf8(gid)).build())
            .build()
        return Content.newBuilder().setActivityNotice(notice).build()
    }

    companion object {
        private const val MY_ID = "+10000000"
        private const val TAG = "TestTag"
        private const val REQUEST_ID = 1L
    }
}
