package com.difft.android.chat.messages

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

/**
 * Integration tests for the DataMessage cross-conversation injection guard mounted in
 * [MessageContentProcessor.handleMessage] `hasDataMessage()` branch (design §4.2/§7.4).
 *
 * The mount calls `crossCheckConversation(content.conversation, content.envelopeConversation,
 * senderId==myId)`; REJECT => `return null` before `handleDataMessage`. The proceed/drop probe is
 * `asyncMessageJobsManager.makeSureGroupExist(gid)` — the first side effect inside
 * `handleDataMessage` (MessageContentProcessor.kt:450): called exactly once => PROCESS reached a
 * group message; zero => dropped at the mount. For 1v1, a proceed builds a non-null TextMessage.
 *
 * Pattern mirrors [MessageContentProcessorForwardNoticeTest] (Robolectric + relaxed mocks + real
 * Gson + mockkStatic globalServices/WCDBExtensions).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageContentProcessorConversationCrossCheckTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Application
    private lateinit var processor: MessageContentProcessor
    private lateinit var asyncMessageJobsManager: AsyncMessageJobsManager
    private lateinit var localMessageCreator: LocalMessageCreator
    private lateinit var globalServicesMock: GlobalHiltEntryPoint

    // Real EnvelopToMessageProcessor wrapping the real MessageContentProcessor, with only
    // decryption + persistence mocked. Lets IT-ACK / IT-PREVIEW-* drive the actual
    // process() -> classify chain (IT-ACK) and the actual endReceive C2 wiring (IT-PREVIEW-*).
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

        // Only the non-interference tests (forwardNotice/activityNotice) touch wcdb.isGroupMember.
        // Stubbing the extension avoids WCDB native Table linking (see ForwardNoticeTest).
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
            localMessageCreator = localMessageCreator,
            groupCryptoRepo = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
            weakContactReconciler = mockk(relaxed = true),
            gson = Gson(),
        )

        // Real receive-path chain around the real processor: decrypt + persist mocked only.
        decryptionUtil = mockk(relaxed = true)
        envelopeDbMessageStore = mockk(relaxed = true)
        envelopToMessageProcessor = EnvelopToMessageProcessor(
            newMessageDecryptionUtil = decryptionUtil,
            messageContentProcessor = processor,
            dbMessageStore = envelopeDbMessageStore,
        )
        incomingConversationProcessor = IncomingConversationMessageProcessor(
            webSocket = mockk(relaxed = true),
            dbRoomStore = mockk(relaxed = true),
            envelopToMessageProcessor = envelopToMessageProcessor,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ------------------------------------------------------------------
    // REJECT (injection) — cross-shape and gid-mismatch, all dropped.
    // ------------------------------------------------------------------

    // IT-INJECT-1v1: the core exploit. Non-member PUTs a group DataMessage through the 1v1
    // endpoint; server stamps the real (1v1) channel. Cross-shape mismatch => drop, no group touch.
    @Test
    fun `IT-INJECT-1v1 group content via 1v1 envelope is rejected`() = runTest {
        val attacker = "+19990001"
        val victimGroup = "a".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        val content = groupData(victimGroup, body = "hi")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNull(result)
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }

    // IT-INJECT-G≠H: content group G but server stamped a different group H => reject.
    @Test
    fun `IT-INJECT content group differs from envelope group is rejected`() = runTest {
        val peer = "+15551111"
        val contentGroup = "a".repeat(32)
        val envelopeGroup = "b".repeat(32)
        val env = envelopeWith(source = peer, conv = For.Group(envelopeGroup))
        val content = groupData(contentGroup, body = "hi")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNull(result)
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }

    // IT-INJECT-1v1-vs-Genv: symmetric cross-shape — 1v1 content, group envelope => reject.
    // A legal 1v1 would build a non-null TextMessage, so result==null proves the drop.
    @Test
    fun `IT-INJECT 1v1 content via group envelope is rejected`() = runTest {
        val peer = "+15552222"
        val envelopeGroup = "c".repeat(32)
        val env = envelopeWith(source = peer, conv = For.Group(envelopeGroup))
        val content = oneToOneData()

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNull(result)
    }

    // ------------------------------------------------------------------
    // PROCESS (present-match) — inner conversation equals server-stamped outer.
    // ------------------------------------------------------------------

    // IT-PASS-GROUP: legal group, inner==outer (same 32 UTF-8 bytes). Pins gid byte-identity
    // across the content path (:160) and the envelope path (parseToFor:329) at the live mount.
    @Test
    fun `IT-PASS-GROUP legal group inner equals outer is processed`() = runTest {
        val peer = "+15553333"
        val group = "d".repeat(32)
        val env = envelopeWith(source = peer, conv = For.Group(group))
        val content = groupData(group, body = "hi")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
        assertNotNull(result)
    }

    // IT-PASS-1v1-STAMP: legal 1v1 — server stamps conversationId.number = sender-from-receiver-view
    // (== senderId). Present-match 1v1 => PROCESS. Pins the 1v1-stamp contract (§7.6#5).
    @Test
    fun `IT-PASS-1v1 legal 1v1 with matching sender stamp is processed`() = runTest {
        val peer = "+15554444"
        val env = envelopeWith(source = peer, conv = For.Account(peer))
        val content = oneToOneData()

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNotNull(result)
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }

    // ------------------------------------------------------------------
    // ABSENT (fail-open) — server has not stamped conversationId => PROCESS. Today's steady state.
    // ------------------------------------------------------------------

    @Test
    fun `IT-ABSENT-GROUP no envelope conversationId is processed`() = runTest {
        val peer = "+15555555"
        val group = "e".repeat(32)
        val env = envelopeWith(source = peer, conv = null)
        val content = groupData(group, body = "hi")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
        assertNotNull(result)
    }

    @Test
    fun `IT-ABSENT-1v1 no envelope conversationId is processed`() = runTest {
        val peer = "+15556666"
        val env = envelopeWith(source = peer, conv = null)
        val content = oneToOneData()

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNotNull(result)
    }

    // ------------------------------------------------------------------
    // Exemption — senderId==myId wins over any mismatch; sync path bypasses the mount entirely.
    // Both use empty-body group content: makeSureGroupExist fires (proceed), then the empty message
    // drops at :722 BEFORE the self-sent-group member lookup (:745, native WCDB) — irrelevant here.
    // ------------------------------------------------------------------

    // IT-SELF-GROUP: self-sent v4 group message via the normal data path, mismatched stamp.
    // Exemption wins over the Group-vs-Account mismatch => PROCESS.
    @Test
    fun `IT-SELF-GROUP self-sent group is processed despite mismatch`() = runTest {
        val group = "f".repeat(32)
        val env = envelopeWith(source = MY_ID, conv = For.Account("+10009999"))
        val content = groupData(group, body = "")

        processor.process(SignalServiceDataClass(env, content, null), TAG)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
    }

    // IT-SYNC-NOTCHECK: sync-sent group message (senderId==myId) proceeds via the hasSyncMessage()
    // branch — the mount is on the non-sync branch only, so a mismatched stamp is never checked.
    @Test
    fun `IT-SYNC-NOTCHECK sync-sent group bypasses the mount`() = runTest {
        val group = "g".repeat(32)
        val env = envelopeWith(source = MY_ID, conv = For.Account("+10009999"))
        val content = syncSentGroup(group)

        processor.process(SignalServiceDataClass(env, content, null), TAG)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
    }

    // ------------------------------------------------------------------
    // Recall — the cross-check is additive: it fires at the mount, before the existing
    // recall `source==sender` gate (handleTextMessage:681), and does not weaken it.
    // ------------------------------------------------------------------

    // IT-RECALL-REJECT: injected recall via the wrong (1v1) channel is dropped at the mount,
    // before the recall source check ever runs.
    @Test
    fun `IT-RECALL-REJECT recall via wrong channel is rejected at mount`() = runTest {
        val attacker = "+19990003"
        val victimGroup = "h".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        val content = groupData(victimGroup, recallSource = "+18880001")

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNull(result)
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }

    // IT-RECALL-AUTH: recall on its legal channel with wrong source. The cross-check PASSES
    // (makeSureGroupExist fires => it did not break the legal recall flow), and the existing
    // `recall.source != senderId` gate still drops it (result==null).
    @Test
    fun `IT-RECALL-AUTH legal channel wrong source still dropped by source gate`() = runTest {
        val member = "+15557777"
        val group = "i".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = groupData(group, recallSource = "+18880002") // source != member

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
        assertNull(result)
    }

    // ------------------------------------------------------------------
    // C3 (PendingMessageHelper.buildEnvelope re-synthesis) — the manufactured-envelope path.
    // ------------------------------------------------------------------

    // IT-C3-BUILDENV: buildEnvelope encodes msgExtra.conversationId.groupId via
    // transformGroupIdFromLocalToServer (aligned to C2's copyWithMsgExtraConversationId). Assert the
    // stamped bytes match C2's encoder, then drive the built envelope through the mount for a
    // plain-ASCII group => PROCESS (round-trips identically to the content side, no false-drop).
    @Test
    fun `IT-C3-BUILDENV buildEnvelope round-trips identically to content side`() = runTest {
        val peer = "+15558888"
        val group = "j".repeat(32)

        val helper = PendingMessageHelper(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val pending = PendingMessage(
            type = 1, relay = null, timestamp = 100L, source = peer, sourceDevice = 1,
            content = null, systemShowTimestamp = 100L, sequenceId = 0L, notifySequenceId = 0L,
            msgType = 1, conversation = group, identityKey = null, peerContext = null,
        )
        val method = PendingMessageHelper::class.java
            .getDeclaredMethod("buildEnvelope", PendingMessage::class.java)
            .apply { isAccessible = true }
        val built = method.invoke(helper, pending) as Envelope

        // C3 encoder == C2 encoder (both route the local id through transformGroupIdFromLocalToServer).
        val c2Bytes = envelopeWith(peer, null)
            .copyWithMsgExtraConversationId(For.Group(group))
            .msgExtra.conversationId.groupId.toByteArray()
        assertArrayEquals(c2Bytes, built.msgExtra.conversationId.groupId.toByteArray())

        // The built envelope's stamp decodes back to For.Group(group) == content side => PROCESS.
        val result = processor.process(SignalServiceDataClass(built, groupData(group, body = "hi"), null), TAG)
        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
        assertNotNull(result)
    }

    // ------------------------------------------------------------------
    // ForwardNotice cross-check (design §14.2). Same mount helper (isCrossConversationInjection) as
    // DataMessage, resolving forwardNotice.conversation via parseToFor on both sides. The conversation
    // cross-check is the sole cross-conversation admission gate; membership is not consulted for a
    // present-match forward. Proceed/drop probe is
    // localMessageCreator.createForwardNoticeMessage (fires only when the handler is reached).
    // ------------------------------------------------------------------

    // FWD-INJECT: non-member injects a forward notice for group G through a 1v1 envelope.
    // content=For.Group(G) vs envelope=For.Account(attacker) => cross-shape mismatch => REJECT.
    @Test
    fun `FWD-INJECT group forward via 1v1 envelope is rejected`() = runTest {
        val attacker = "+19990101"
        val group = "p".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // FWD-PASS: legal group forward, content group == server-stamped envelope group => PROCESS.
    @Test
    fun `FWD-PASS legal group forward inner equals outer is processed`() = runTest {
        val member = "+15551111"
        val group = "q".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // FWD-ABSENT: server has not stamped conversationId (old/transitional server) => fail-open PROCESS.
    @Test
    fun `FWD-ABSENT no envelope conversationId is processed`() = runTest {
        val peer = "+15551212"
        val group = "r".repeat(32)
        val env = envelopeWith(source = peer, conv = null)
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // FWD-REGRESS-REMOVED-GUARD: a legit forward (env stamped G, present-match) renders even though
    // isGroupMember is NOT stubbed (returns false). The conversation cross-check is the sole admission
    // gate; membership is not consulted for a present-match forward, so isGroupMember is never called.
    @Test
    fun `FWD-REGRESS-REMOVED-GUARD legit forward renders without roster guard`() = runTest {
        val member = "+15551313"
        val group = "s".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { wcdb.isGroupMember(any(), any()) }
    }

    // FWD-INJECT-G≠H: content forward names group G but the server stamped a DIFFERENT group H.
    // Same-shape For.Group vs For.Group mismatch (not cross-shape) => REJECT, mirroring the
    // DataMessage IT-INJECT-G≠H row through the forward wiring.
    @Test
    fun `FWD-INJECT content group differs from envelope group is rejected`() = runTest {
        val member = "+15551414"
        val contentGroup = "x".repeat(32)
        val envelopeGroup = "y".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(envelopeGroup))
        val content = forwardNoticeGroup(contentGroup)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // FWD-SELF: self-authored forward (senderId==myId) with a deliberately MISMATCHED stamp.
    // The isSyncOrSelf exemption wins over the mismatch => PROCESS, mirroring IT-SELF-GROUP
    // through the forward wiring.
    @Test
    fun `FWD-SELF self-authored forward is processed despite mismatch`() = runTest {
        val group = "z".repeat(32)
        val env = envelopeWith(source = MY_ID, conv = For.Account("+10009999"))
        val content = forwardNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ------------------------------------------------------------------
    // ActivityNotice cross-check (design §14.3) — mirror of ForwardNotice. Membership is not
    // consulted; same mount helper. Proceed/drop probe is localMessageCreator.createActivityNoticeMessage.
    // ------------------------------------------------------------------

    // ACT-INJECT: non-member injects an activity notice for group G through a 1v1 envelope => REJECT.
    @Test
    fun `ACT-INJECT group activity via 1v1 envelope is rejected`() = runTest {
        val attacker = "+19990202"
        val group = "t".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        val content = activityNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNull(result)
        coVerify(exactly = 0) {
            localMessageCreator.createActivityNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ACT-PASS: legal group activity, content group == envelope group => PROCESS.
    @Test
    fun `ACT-PASS legal group activity inner equals outer is processed`() = runTest {
        val member = "+15552121"
        val group = "u".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = activityNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createActivityNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ACT-ABSENT: server has not stamped conversationId => fail-open PROCESS.
    @Test
    fun `ACT-ABSENT no envelope conversationId is processed`() = runTest {
        val peer = "+15552323"
        val group = "v".repeat(32)
        val env = envelopeWith(source = peer, conv = null)
        val content = activityNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createActivityNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ACT-REGRESS-REMOVED-GUARD: a legit activity notice renders with isGroupMember NOT stubbed
    // (returns false) => the cross-check is the sole admission gate; membership is not consulted.
    @Test
    fun `ACT-REGRESS-REMOVED-GUARD legit activity renders without roster guard`() = runTest {
        val member = "+15552424"
        val group = "w".repeat(32)
        val env = envelopeWith(source = member, conv = For.Group(group))
        val content = activityNoticeGroup(group)

        val result = processor.process(SignalServiceDataClass(env, content, null), TAG)

        assertNotNull(result)
        coVerify(exactly = 1) {
            localMessageCreator.createActivityNoticeMessage(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { wcdb.isGroupMember(any(), any()) }
    }

    // ------------------------------------------------------------------
    // C2 (conversation-preview / IncomingConversationMessageProcessor.endReceive) — the one
    // channel that ALWAYS stamps msgExtra.conversationId (copyWithMsgExtraConversationId), so it
    // has NO absent->PROCESS fail-open net and enforces on merge (design §9/§12). These drive the
    // REAL endReceive wiring: income() -> endReceive() -> envelopToMessageProcessor.process() ->
    // (decrypt stubbed) -> the real MessageContentProcessor mount. decrypt() `answers` with a
    // SignalServiceDataClass wrapping the ACTUAL latestMsg endReceive built via
    // copyWithMsgExtraConversationId(forWhat) — so the real C2 re-stamp/round-trip feeds the
    // cross-check, not a re-synthesized shortcut. endReceive returns Unit, so the proceed/drop
    // probe is makeSureGroupExist (fires inside handleDataMessage) exactly as the generic mount.
    // ------------------------------------------------------------------

    // IT-PREVIEW-GROUP: a genuine peer-authored group preview. conversationPreview.conversationId
    // names group G; latestMsg content resolves to G. The C2 re-stamp round-trips identically to
    // the content side (plain-ASCII f∘g identity) => match => PROCESS, no false-reject.
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

    // IT-PREVIEW-SELF: a self-authored preview (sender==myId) with a deliberately MISMATCHED
    // stamp (conversationId.number != the group content). Mirrors IT-SELF-GROUP through the C2
    // wiring: the senderId==myId exemption wins over the Group-vs-Account mismatch => the message
    // is NOT cross-checked/rejected => PROCESS. Uses group content because endReceive returns Unit
    // and makeSureGroupExist is the only clean proceed probe on this path (empty body drops after
    // makeSureGroupExist, mirroring IT-SELF-GROUP).
    @Test
    fun `IT-PREVIEW-SELF self-authored preview is not cross-checked`() = runTest {
        val group = "o".repeat(32)
        every { decryptionUtil.decrypt(any()) } answers {
            SignalServiceDataClass(firstArg(), groupData(group, body = ""), null)
        }

        // Self-sent: latestMsg.source == MY_ID; stamp is a mismatched 1v1 number.
        val preview = conversationPreview(source = MY_ID, conv = For.Account("+10009999"))
        incomingConversationProcessor.income(ConversationPreviewWrapper(preview), REQUEST_ID)
        incomingConversationProcessor.endReceive(REQUEST_ID)

        coVerify(exactly = 1) { asyncMessageJobsManager.makeSureGroupExist(group) }
    }

    // ------------------------------------------------------------------
    // IT-ACK — the "never throw on REJECT" flow pin (design §5/§6). Drives a REJECT through the
    // REAL EnvelopToMessageProcessor.process -> classify chain (decrypt stubbed to yield the
    // injection SignalServiceDataClass). The real MessageContentProcessor cross-check returns null
    // (return null, not throw) => processContentToMessage short-circuits => Success(null), NOT
    // TransientFailure (which would re-queue/redeliver) and NOT persisted (eager-ACK consumed).
    // ------------------------------------------------------------------

    @Test
    fun `IT-ACK REJECT resolves to Success null not a retry and is not persisted`() = runTest {
        val attacker = "+19990009"
        val victimGroup = "m".repeat(32)
        val env = envelopeWith(source = attacker, conv = For.Account(attacker))
        every { decryptionUtil.decrypt(any()) } returns
            SignalServiceDataClass(env, groupData(victimGroup, body = "hi"), null)

        val res = envelopToMessageProcessor.process(env, TAG)

        // Drop maps to ACK-consumed Success(null), never the TransientFailure retry queue.
        assertTrue(res is EnvelopeProcessResult.Success)
        assertNull((res as EnvelopeProcessResult.Success).result)
        // No render, no persist, no group touch.
        verify(exactly = 0) { envelopeDbMessageStore.putWhenNonExist(any<Message>()) }
        coVerify(exactly = 0) { asyncMessageJobsManager.makeSureGroupExist(any()) }
    }

    // -------- helpers --------

    /** Envelope with source + optional server-stamped msgExtra.conversationId (For -> number/groupId). */
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

    /**
     * ConversationPreview whose [lastestMsg] is a bare envelope (source only, no msgExtra —
     * endReceive stamps it via copyWithMsgExtraConversationId) and whose conversationId names
     * [conv] (groupId for For.Group, number for For.Account), matching what endReceive reads at
     * IncomingConversationMessageProcessor.kt:40-44.
     */
    private fun conversationPreview(source: String, conv: For, ts: Long = 100L): ConversationPreview {
        val cid = ConversationId.newBuilder()
        if (conv is For.Group) cid.groupId = ByteString.copyFromUtf8(conv.id) else cid.number = conv.id
        return ConversationPreview.newBuilder()
            .setConversationId(cid.build())
            .setLastestMsg(envelopeWith(source = source, conv = null, ts = ts))
            .build()
    }

    /** DataMessage Content for group [gid]; optional body and recall.source. */
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

    /** Plain 1v1 DataMessage Content (no group) -> conversation resolves to For.Account(senderId). */
    private fun oneToOneData(body: String = "hi"): Content =
        Content.newBuilder().setDataMessage(DataMessage.newBuilder().setBody(body).build()).build()

    /** Sync-sent group DataMessage (self path) -> reaches handleDataMessage via the sync branch. */
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

    /** Top-level forwardNotice (COMBINED scene) for group [gid]. */
    private fun forwardNoticeGroup(gid: String): Content {
        val forwardNotice = org.whispersystems.signalservice.internal.push.SignalServiceProtos.ForwardNoticeMessage.newBuilder()
            .setScene(org.whispersystems.signalservice.internal.push.SignalServiceProtos.ForwardNoticeMessage.ForwardScene.COMBINED)
            .addSourceAuthorIds("+12001")
            .setMessageCount(1)
            .setConversation(ConversationId.newBuilder().setGroupId(ByteString.copyFromUtf8(gid)).build())
            .build()
        return Content.newBuilder().setForwardNotice(forwardNotice).build()
    }

    /** Top-level activityNotice (COPYDATA) for group [gid]. */
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
