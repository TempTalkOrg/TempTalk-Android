package com.difft.android.chat.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.google.gson.Gson
import com.google.protobuf.ByteString
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardNoticeData
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
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.difft.app.database.isGroupMember
import org.difft.app.database.wcdb
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ForwardNoticeMessage

/**
 * Integration tests for the primary `Content.forwardNotice` branch of
 * [MessageContentProcessor.handleMessage] — the "receive from peer / group /
 * self-as-NTS" path. 1v1-to-other self-sync tests
 * (`Content.syncMessage.forwardNoticeSync`) live in the sibling file
 * [MessageContentProcessorForwardNoticeSyncTest] to keep both under the
 * 500-line project limit.
 *
 * Conversation is resolved by [SignalServiceDataClass.conversation] lazy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageContentProcessorForwardNoticeTest {

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

        // handleForwardNoticeMessage validates group membership via `wcdb.isGroupMember`
        // (cross-conversation injection guard). The `wcdb` top-level property and the
        // extension live in WCDBExtensions.kt → compiled class is `WCDBExtensionsKt`.
        // Each group-scene test stubs membership for its own peer id via
        // `stubSenderIsMember(...)`; default (no stub) returns null → NOT a member → drop.
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")

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
            gson = Gson(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ------------------------------------------------------------------
    // Primary, group path — payload.conversation.groupId resolves to For.Group.
    // Operator = envelope.source (the peer who sent it).
    // ------------------------------------------------------------------
    @Test
    fun `primary group path uses payload groupId and operator from envelope`() = runTest {
        val peer = "+15551111"
        val groupIdAscii = "a".repeat(32)
        val timestamp = 1_700_000_000_000L
        val sourceDevice = 2

        // Stub the group-membership check: peer IS a member of groupIdAscii.
        stubSenderIsMember(true)

        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.COMBINED)
            .addAllSourceAuthorIds(listOf("+12001", "+12002"))
            .setMessageCount(3)
            .setConversation(
                ConversationId.newBuilder()
                    .setGroupId(ByteString.copyFromUtf8(groupIdAscii))
                    .build()
            )
            .build()

        val envelope = Envelope.newBuilder()
            .setSource(peer)
            .setSourceDevice(sourceDevice)
            .setTimestamp(timestamp)
            .setSystemShowTimestamp(timestamp)
            .build()
        val content = Content.newBuilder().setForwardNotice(forwardNotice).build()

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG)
        assertSame(fakeNotifyResult, result)

        val operatorSlot = slot<String>()
        val forWhatSlot = slot<For>()
        val noticeSlot = slot<ForwardNoticeData>()
        val systemTsSlot = slot<Long>()
        val tsSlot = slot<Long>()
        val deviceSlot = slot<Int>()
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(
                operatorId = capture(operatorSlot),
                forWhat = capture(forWhatSlot),
                noticeData = capture(noticeSlot),
                systemShowTimestamp = capture(systemTsSlot),
                timestamp = capture(tsSlot),
                sourceDevice = capture(deviceSlot)
            )
        }
        assertEquals(peer, operatorSlot.captured)
        assertEquals(For.Group(groupIdAscii), forWhatSlot.captured)
        assertEquals(ForwardNoticeData.Scene.COMBINED, noticeSlot.captured.scene)
        assertEquals(listOf("+12001", "+12002"), noticeSlot.captured.sourceAuthorIds)
        assertEquals(3, noticeSlot.captured.messageCount)
        assertEquals(timestamp, systemTsSlot.captured)
        assertEquals(timestamp, tsSlot.captured)
        assertEquals(sourceDevice, deviceSlot.captured)
    }

    // ------------------------------------------------------------------
    // Primary 1v1 — envelope.source IS the peer; operator = envelope.source.
    // ------------------------------------------------------------------
    @Test
    fun `primary 1v1 uses envelope source as peer`() = runTest {
        val peer = "+15552222"
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.SINGLE)
            .addSourceAuthorIds("+13001")
            .setMessageCount(1)
            .build()
        val envelope = minimalEnvelope(peer, 100L)
        val content = Content.newBuilder().setForwardNotice(forwardNotice).build()

        processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        val forWhatSlot = slot<For>()
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(
                operatorId = eq(peer),
                forWhat = capture(forWhatSlot),
                noticeData = any(),
                systemShowTimestamp = any(),
                timestamp = any(),
                sourceDevice = any()
            )
        }
        assertEquals(For.Account(peer), forWhatSlot.captured)
    }

    // ------------------------------------------------------------------
    // Unknown / unspecified scene — both "scene unset" and "future enum value
    // we don't recognize" decode to UNKNOWN in proto2-lite. Receiver must drop
    // rather than silently rendering as some known scene.
    // ------------------------------------------------------------------
    @Test
    fun `forwardNotice drops silently when scene is UNKNOWN`() = runTest {
        val peer = "+15551111"
        // Explicit UNKNOWN (also equivalent to "scene field not set")
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.UNKNOWN)
            .addSourceAuthorIds("+16001")
            .setMessageCount(1)
            .build()
        val envelope = minimalEnvelope(peer, 600L)
        val content = Content.newBuilder().setForwardNotice(forwardNotice).build()

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG)
        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ------------------------------------------------------------------
    // NTS source primary path — sender sends Content.forwardNotice to recipient=self.
    // Receiver (my other device) sees envelope.source == myId; conversation resolves to
    // For.Account(myId) = Note-to-Self. Does NOT go through SyncMessage wrapper.
    // ------------------------------------------------------------------
    @Test
    fun `NTS source primary resolves to self account`() = runTest {
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.SAVE_TO_NOTES)
            .addSourceAuthorIds(MY_ID)
            .setMessageCount(1)
            .setConversation(ConversationId.newBuilder().setNumber(MY_ID).build())
            .build()
        val envelope = minimalEnvelope(MY_ID, 300L)
        val content = Content.newBuilder().setForwardNotice(forwardNotice).build()

        processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        val forWhatSlot = slot<For>()
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(
                operatorId = eq(MY_ID),
                forWhat = capture(forWhatSlot),
                noticeData = any(),
                systemShowTimestamp = any(),
                timestamp = any(),
                sourceDevice = any()
            )
        }
        assertEquals(For.Account(MY_ID), forWhatSlot.captured)
    }

    // ------------------------------------------------------------------
    // messageCount == 0 protocol-violation degrade → coerced to max(1, authorCount).
    // ------------------------------------------------------------------
    @Test
    fun `handleMessage degrades messageCount 0 to 1`() = runTest {
        // Receiver coerces messageCount to >= 1 so plurals always renders. Does NOT
        // raise to authorIds.size — a peer could otherwise craft messageCount=1 with
        // 100 authors to inflate the displayed count.
        val peer = "+15553333"
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.ONE_BY_ONE)
            .addAllSourceAuthorIds(listOf("+14001", "+14002", "+14003"))
            .setMessageCount(0) // protocol violation
            .build()
        val envelope = minimalEnvelope(peer, 100L)
        val content = Content.newBuilder().setForwardNotice(forwardNotice).build()

        processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        val noticeSlot = slot<ForwardNoticeData>()
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(
                operatorId = any(),
                forWhat = any(),
                noticeData = capture(noticeSlot),
                systemShowTimestamp = any(),
                timestamp = any(),
                sourceDevice = any()
            )
        }
        // max(1, 0) = 1 — authorIds.size is NOT used to raise the floor.
        assertEquals(1, noticeSlot.captured.messageCount)
    }

    @Test
    fun `handleMessage does not let authorIds size inflate messageCount`() = runTest {
        // Attacker scenario: messageCount=1 with 5 authors. Receiver must show 1,
        // not 5. authorIds.size is NOT allowed to drive messageCount.
        val peer = "+15554444"
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.SINGLE)
            .addAllSourceAuthorIds(listOf("+14001", "+14002", "+14003", "+14004", "+14005"))
            .setMessageCount(1)
            .build()
        val envelope = minimalEnvelope(peer, 100L)
        val content = Content.newBuilder().setForwardNotice(forwardNotice).build()

        processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        val noticeSlot = slot<ForwardNoticeData>()
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(
                operatorId = any(),
                forWhat = any(),
                noticeData = capture(noticeSlot),
                systemShowTimestamp = any(),
                timestamp = any(),
                sourceDevice = any()
            )
        }
        assertEquals(1, noticeSlot.captured.messageCount)
    }

    // ------------------------------------------------------------------
    // Timestamp fallback: systemShowTimestamp == 0 → use timestamp.
    // ------------------------------------------------------------------
    @Test
    fun `handleForwardNoticeMessage fallbacks systemShowTimestamp to timestamp when zero`() = runTest {
        val peer = "+15558888"
        val ts = 1_700_000_000_555L
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.SINGLE)
            .addSourceAuthorIds("+17001")
            .setMessageCount(1)
            .build()
        val envelope = Envelope.newBuilder()
            .setSource(peer)
            .setSourceDevice(1)
            .setTimestamp(ts)
            // NOT setting systemShowTimestamp → default 0
            .build()
        val content = Content.newBuilder().setForwardNotice(forwardNotice).build()

        processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        val sysTsSlot = slot<Long>()
        val tsSlot = slot<Long>()
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(
                operatorId = any(),
                forWhat = any(),
                noticeData = any(),
                systemShowTimestamp = capture(sysTsSlot),
                timestamp = capture(tsSlot),
                sourceDevice = any()
            )
        }
        assertEquals(
            "when envelope.systemShowTimestamp is 0, must fall back to timestamp",
            ts, sysTsSlot.captured
        )
        assertEquals(ts, tsSlot.captured)
    }

    // ------------------------------------------------------------------
    // Cross-conversation injection guard: peer sends `Content.forwardNotice`
    // with payload.groupId = <some group the victim is in> BUT peer is NOT a
    // member of that group. Handler must drop, not insert the fake notice
    // into that group on the victim's device.
    // ------------------------------------------------------------------
    @Test
    fun `forwardNotice drops when envelope source is not a member of the target group`() = runTest {
        val spoofingPeer = "+19990001"
        val victimGroup = "b".repeat(32)
        // Envelope.source (spoofingPeer) is NOT in the group member list
        stubSenderIsMember(false)

        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.SINGLE)
            .addSourceAuthorIds("+19990002")
            .setMessageCount(1)
            .setConversation(ConversationId.newBuilder().setGroupId(ByteString.copyFromUtf8(victimGroup)).build())
            .build()
        val envelope = minimalEnvelope(spoofingPeer, 700L)
        val content = Content.newBuilder().setForwardNotice(forwardNotice).build()

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG)
        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ------------------------------------------------------------------
    // The receiver does NOT truncate `sourceAuthorIds` at the processor
    // boundary. Display truncation lives in ForwardNoticeRenderer, and only
    // the rendered text is persisted — the raw list never hits the DB, so
    // preserving it in memory keeps the renderer's `size > cap` signal
    // meaningful (without it, the ellipsis could never render on receivers).
    // ------------------------------------------------------------------
    @Test
    fun `forwardNotice passes sourceAuthorIds through unchanged on receive`() = runTest {
        val peer = "+15557777"
        // Peer sends 10 authors. Processor forwards all 10; renderer caps display.
        val sentAuthors = (1..10).map { "+2000$it" }
        val forwardNotice = ForwardNoticeMessage.newBuilder()
            .setScene(ForwardNoticeMessage.ForwardScene.COMBINED)
            .addAllSourceAuthorIds(sentAuthors)
            .setMessageCount(10)
            .build()
        val envelope = minimalEnvelope(peer, 800L)
        val content = Content.newBuilder().setForwardNotice(forwardNotice).build()

        processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        val noticeSlot = slot<ForwardNoticeData>()
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(
                operatorId = any(),
                forWhat = any(),
                noticeData = capture(noticeSlot),
                systemShowTimestamp = any(),
                timestamp = any(),
                sourceDevice = any()
            )
        }
        assertEquals(sentAuthors, noticeSlot.captured.sourceAuthorIds)
        assertEquals(10, noticeSlot.captured.messageCount)
    }

    // -------- helpers --------
    private fun minimalEnvelope(source: String, ts: Long): Envelope = Envelope.newBuilder()
        .setSource(source)
        .setSourceDevice(1)
        .setTimestamp(ts)
        .setSystemShowTimestamp(ts)
        .build()

    /**
     * Stubs `wcdb.isGroupMember(gid, userId)` directly. Stubbing the chain
     * `wcdb.groupMemberContactor.getFirstObject(...)` triggers WCDB `Table`
     * native linking in the relaxed mock (UnsatisfiedLinkError), so we target
     * the extension — `mockkStatic("org.difft.app.database.WCDBExtensionsKt")`
     * already enables this — and short-circuit the Table layer entirely.
     */
    private fun stubSenderIsMember(senderIsMember: Boolean) {
        every { wcdb.isGroupMember(any(), any()) } returns senderIsMember
    }

    companion object {
        private const val MY_ID = "+10000000"
        private const val TAG = "TestTag"
    }
}
