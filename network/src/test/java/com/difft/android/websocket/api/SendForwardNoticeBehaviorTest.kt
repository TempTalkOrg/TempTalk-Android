package com.difft.android.websocket.api

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.network.signal.MessageSendRepository
import com.difft.android.websocket.api.messages.PublicKeyInfo
import com.difft.android.websocket.api.services.NewMessagingService
import com.difft.android.websocket.api.util.EncryptResult
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import com.difft.android.websocket.internal.push.NewSendMessageResponse
import difft.android.messageserialization.For
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.conversationId
import org.whispersystems.signalservice.internal.push.forwardNoticeMessage
import java.io.IOException

/**
 * Behavioral contract for [NewSignalServiceMessageSender.sendForwardNoticeMessage].
 *
 * Locks in the v2 design (issue #695): one HTTP request per send, with the self-sync
 * envelope piggybacked via [NewOutgoingPushMessage.syncContent] for 1v1-to-other.
 * The pre-#695 implementation issued TWO `sendMessage(...)` calls; if anyone
 * re-introduces that pattern these tests fail loud.
 *
 * The WS path is bypassed by throwing IOException from [messagingService.send] /
 * [messagingService.sendToGroup] so the HTTP fallback ([messageSendRepository.sendMessage])
 * fires — that's where we capture [NewOutgoingPushMessage] for assertions. Same trick
 * as [NewSignalServiceMessageSenderRetryTest]; full WS plumbing isn't needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendForwardNoticeBehaviorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val messagingService: NewMessagingService = mockk(relaxed = true)
    private val messageEncryptor: INewMessageContentEncryptor = mockk(relaxed = true)
    private val conversationManager: ConversationManager = mockk(relaxed = true)
    private val messageSendRepository: MessageSendRepository = mockk(relaxed = true)

    private lateinit var sender: NewSignalServiceMessageSender

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val mockGlobal = mockk<GlobalHiltEntryPoint>(relaxed = true).also {
            every { it.myId } returns SELF_UID
        }
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobal

        sender = NewSignalServiceMessageSender(
            messagingService = messagingService,
            maxEnvelopeSize = 65_536L,
            messageEncryptor = messageEncryptor,
            conversationManager = conversationManager,
            messageSendRepository = messageSendRepository,
        )

        // Public key info present for both peer and self — generateForwardNoticeSyncContent
        // depends on the self-key being non-blank.
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns true
        coEvery { conversationManager.getPublicKeyInfos(any<For>()) } returns listOf(
            PublicKeyInfo(uid = PEER_UID, identityKey = "k-peer", registrationId = 1, resetIdentityKeyTime = 0),
            PublicKeyInfo(uid = SELF_UID, identityKey = "k-self", registrationId = 1, resetIdentityKeyTime = 0),
        )
        every {
            messageEncryptor.encryptOneToOneMessage(any(), any())
        } returns stubEncryptResult()
        every {
            messageEncryptor.encryptGroupMessage(any(), any())
        } returns stubEncryptResult()

        // Force HTTP fallback by failing the WS path. The HTTP path is what the
        // tests inspect (see capturePushMessageOnHttp).
        coEvery { messagingService.send(any(), any()) } throws IOException("ws down")
        coEvery { messagingService.sendToGroup(any(), any()) } throws IOException("ws down")
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        Dispatchers.resetMain()
        clearMocks(messagingService, messageEncryptor, conversationManager, messageSendRepository)
    }

    // -----------------------------------------------------------------
    // 1v1-to-other: ONE network call carrying syncContent. Pre-#695 made two.
    // -----------------------------------------------------------------
    @Test
    fun `1v1 to other peer sends one request with non-null syncContent`() = runTest {
        val captured = slot<NewOutgoingPushMessage>()
        coEvery {
            messageSendRepository.sendMessage(capture(captured), any())
        } returns successResponse()

        sender.sendForwardNoticeMessage(
            recipient = For.Account(PEER_UID),
            room = For.Account(PEER_UID),
            message = sampleProto(),
            sendSyncToSelf = true,
            sendTimestamp = 1_700_000_000_001L,
        )

        // Exactly one HTTP send. Self-sync rides via syncContent on this same request,
        // not a second messageSendRepository.sendMessage() call.
        coVerify(exactly = 1) { messageSendRepository.sendMessage(any(), any()) }
        assertNotNull(
            "1v1-to-other must carry syncContent so server stamps both envelopes uniformly",
            captured.captured.syncContent
        )
    }

    // -----------------------------------------------------------------
    // Group: server-side fan-out via member list covers self's other devices.
    // No syncContent needed.
    // -----------------------------------------------------------------
    @Test
    fun `group send issues one request with null syncContent`() = runTest {
        val captured = slot<NewOutgoingPushMessage>()
        coEvery {
            messageSendRepository.sendMessage(capture(captured), any())
        } returns successResponse()

        sender.sendForwardNoticeMessage(
            recipient = For.Group(GROUP_ID),
            room = For.Group(GROUP_ID),
            message = sampleProto(),
            sendSyncToSelf = false,
            sendTimestamp = 1_700_000_000_002L,
        )

        coVerify(exactly = 1) { messageSendRepository.sendMessage(any(), any()) }
        assertNull(
            "Group send must NOT carry syncContent — recipients list already covers sender",
            captured.captured.syncContent
        )
    }

    // -----------------------------------------------------------------
    // Note-to-Self: recipient == self, primary already routes to all my devices.
    // No syncContent needed.
    // -----------------------------------------------------------------
    @Test
    fun `NTS send issues one request with null syncContent`() = runTest {
        val captured = slot<NewOutgoingPushMessage>()
        coEvery {
            messageSendRepository.sendMessage(capture(captured), any())
        } returns successResponse()

        sender.sendForwardNoticeMessage(
            recipient = For.Account(SELF_UID),
            room = For.Account(SELF_UID),
            message = sampleProto(),
            sendSyncToSelf = false,
            sendTimestamp = 1_700_000_000_003L,
        )

        coVerify(exactly = 1) { messageSendRepository.sendMessage(any(), any()) }
        assertNull(
            "NTS send must NOT carry syncContent — primary recipient is self",
            captured.captured.syncContent
        )
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------
    private fun sampleProto(): SignalServiceProtos.ForwardNoticeMessage = forwardNoticeMessage {
        scene = SignalServiceProtos.ForwardNoticeMessage.ForwardScene.COMBINED
        sourceAuthorIds.add("+10001")
        messageCount = 2
        conversation = conversationId {
            number = PEER_UID
        }
    }

    private fun successResponse(): NewSendMessageResponse =
        NewSendMessageResponse().apply {
            status = 0
            data = NewSendMessageResponse.Data().apply {
                systemShowTimestamp = 9_999_999L
            }
        }

    private fun stubEncryptResult() = EncryptResult(
        cipherText = byteArrayOf(1),
        signedEKey = byteArrayOf(2),
        eKey = byteArrayOf(3),
        identityKey = byteArrayOf(4),
        // Cover every uid that appears in `getPublicKeyInfos` — group recipients
        // iterate the full list and dereference `ermKeys[uid]!!` per member.
        ermKeys = mapOf(
            PEER_UID to byteArrayOf(5),
            SELF_UID to byteArrayOf(6),
        ),
    )

    companion object {
        private const val SELF_UID = "selfUid"
        private const val PEER_UID = "peerUid"
        private val GROUP_ID = "g".repeat(32)
    }
}
