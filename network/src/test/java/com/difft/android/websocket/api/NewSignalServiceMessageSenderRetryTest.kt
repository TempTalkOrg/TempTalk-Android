package com.difft.android.websocket.api

import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.network.signal.MessageSendRepository
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.messages.PublicKeyInfo
import com.difft.android.websocket.api.push.exceptions.NoValidRecipientKeysException
import com.difft.android.websocket.api.push.exceptions.PushNetworkException
import com.difft.android.websocket.api.websocket.WebSocketResponseTimeoutException
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import com.difft.android.websocket.api.services.NewMessagingService
import com.difft.android.websocket.api.util.EncryptResult
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import com.difft.android.websocket.api.util.RustEncryptionException
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
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue






@OptIn(ExperimentalCoroutinesApi::class)
class NewSignalServiceMessageSenderRetryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val messagingService: NewMessagingService = mockk(relaxed = true)
    private val messageEncryptor: INewMessageContentEncryptor = mockk(relaxed = true)
    private val conversationManager: ConversationManager = mockk(relaxed = true)
    private val messageSendRepository: MessageSendRepository = mockk(relaxed = true)

    
    private val admission = TestMessagingPause().open().gate.tryAdmit(AdmissionKind.SEND, "test")!!

    private lateinit var sender: NewSignalServiceMessageSender

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        
        
        val mockGlobal = mockk<GlobalHiltEntryPoint>(relaxed = true).also {
            every { it.myId } returns "selfUid"
        }
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobal

        sender = NewSignalServiceMessageSender(
            messagingService = messagingService,
            maxEnvelopeSize = 65536L,
            messageEncryptor = messageEncryptor,
            conversationManager = conversationManager,
            messageSendRepository = messageSendRepository,
        )

        
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns true
        
        
        coEvery { conversationManager.updatePublicKeyInfoDataResult(any()) } returns
            PublicKeyUpdateResult.Updated
        coEvery { conversationManager.classifyEmptyKeys(any()) } returns
            PublicKeyUpdateResult.Updated
        coEvery { conversationManager.getPublicKeyInfos(any<For>()) } returns listOf(
            PublicKeyInfo(uid = "c", identityKey = "k-c", registrationId = 1, resetIdentityKeyTime = 0)
        )
        every {
            messageEncryptor.encryptOneToOneMessage(any(), any())
        } returns stubEncryptResult()
        every {
            messageEncryptor.encryptGroupMessage(any(), any())
        } returns stubEncryptResult()
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        Dispatchers.resetMain()
        clearMocks(messagingService, messageEncryptor, conversationManager, messageSendRepository)
    }

    private fun stubEncryptResult() = EncryptResult(
        cipherText = byteArrayOf(1),
        signedEKey = byteArrayOf(2),
        eKey = byteArrayOf(3),
        identityKey = byteArrayOf(4),
        ermKeys = mapOf("c" to byteArrayOf(5)),
    )

    private fun buildStaleResponse(
        missingUids: List<String>,
        staleUids: List<String>,
        status: Int = 0,
    ): NewSendMessageResponse = NewSendMessageResponse().apply {
        this.status = status
        this.data = NewSendMessageResponse.Data().apply {
            this.missing = missingUids.map { uid ->
                NewSendMessageResponse.User().also { it.uid = uid }
            }
            this.stale = staleUids.map { uid ->
                NewSendMessageResponse.User().also { it.uid = uid }
            }
        }
    }

    private fun buildDataMessage(timestamp: Long = 1000L): SignalServiceProtos.DataMessage =
        SignalServiceProtos.DataMessage.newBuilder()
            .setTimestamp(timestamp)
            .setBody("hello")
            .build()

    
    
    

    @Test
    fun http_fallback_retry_narrows_to_missing_plus_stale_plus_recipient_distinct() = runTest {
        val recipient = For.Account("c")
        val room = recipient
        val missing = listOf("a", "b")
        val stale = emptyList<String>()

        
        coEvery { messagingService.send(any(), any()) } throws IOException("ws down")
        
        coEvery { messageSendRepository.sendMessage(any(), any()) } returnsMany listOf(
            buildStaleResponse(missing, stale),
            NewSendMessageResponse().apply { status = 0; data = NewSendMessageResponse.Data() },
        )

        val capturedUids = slot<List<String>>()
        coEvery { conversationManager.updatePublicKeyInfoData(capture(capturedUids)) } returns true

        runCatching {
            sender.sendDataMessage(recipient, room, buildDataMessage(), null, admission)
        }

        
        assertEquals(setOf("a", "b", "c"), capturedUids.captured.toSet())
        assertEquals(3, capturedUids.captured.size)
        
        coVerify(exactly = 0) { conversationManager.updatePublicKeyInfoData(any<For>()) }
    }

    @Test
    fun http_fallback_retry_status_11001_only_narrows_to_recipient() = runTest {
        val recipient = For.Account("c")
        coEvery { messagingService.send(any(), any()) } throws IOException("ws down")
        coEvery { messageSendRepository.sendMessage(any(), any()) } returnsMany listOf(
            buildStaleResponse(emptyList(), emptyList(), status = 11001),
            NewSendMessageResponse().apply { status = 0; data = NewSendMessageResponse.Data() },
        )

        val capturedUids = slot<List<String>>()
        coEvery { conversationManager.updatePublicKeyInfoData(capture(capturedUids)) } returns true

        runCatching {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }

        assertEquals(setOf("c"), capturedUids.captured.toSet())
        assertEquals(1, capturedUids.captured.size)
    }

    @Test
    fun http_fallback_retry_missing_only() = runTest {
        val recipient = For.Account("c")
        coEvery { messagingService.send(any(), any()) } throws IOException("ws down")
        coEvery { messageSendRepository.sendMessage(any(), any()) } returnsMany listOf(
            buildStaleResponse(listOf("a", "b"), emptyList()),
            NewSendMessageResponse().apply { status = 0; data = NewSendMessageResponse.Data() },
        )

        val capturedUids = slot<List<String>>()
        coEvery { conversationManager.updatePublicKeyInfoData(capture(capturedUids)) } returns true

        runCatching {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }

        assertEquals(setOf("a", "b", "c"), capturedUids.captured.toSet())
    }

    
    
    

    @Test
    fun ws_response_timeout_falls_back_to_http_once_with_same_message() = runTest {
        val recipient = For.Account("c")
        coEvery { messagingService.send(any(), any()) } throws WebSocketResponseTimeoutException(10_000L)
        val sentMessages = mutableListOf<NewOutgoingPushMessage>()
        coEvery { messageSendRepository.sendMessage(capture(sentMessages), any()) } returns
            NewSendMessageResponse().apply { status = 0; data = NewSendMessageResponse.Data() }

        sender.sendDataMessage(recipient, recipient, buildDataMessage(timestamp = 4242L), null, admission)

        coVerify(exactly = 1) { messageSendRepository.sendMessage(any(), any()) }
        assertEquals(1, sentMessages.size)
        assertEquals(4242L, sentMessages.single().timestamp)
    }

    @Test
    fun ws_response_timeout_with_http_failure_propagates_retryable_io_exception() = runTest {
        val recipient = For.Account("c")
        coEvery { messagingService.send(any(), any()) } throws WebSocketResponseTimeoutException(10_000L)
        coEvery { messageSendRepository.sendMessage(any(), any()) } throws PushNetworkException("http down")

        val thrown = assertFailsWith<IOException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }
        assertTrue(thrown is PushNetworkException, "was $thrown")
        coVerify(exactly = 1) { messageSendRepository.sendMessage(any(), any()) }
    }

    
    
    

    @Test
    fun rust_key_data_length_exception_1v1_narrows_to_recipient_only() = runTest {
        val recipient = For.Account("c")
        
        every { messageEncryptor.encryptOneToOneMessage(any(), any()) } throws
            RustEncryptionException(RuntimeException("KeyDataLengthException: key too short"))

        val capturedUids = slot<List<String>>()
        coEvery { conversationManager.updatePublicKeyInfoData(capture(capturedUids)) } returns true

        runCatching {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }

        assertEquals(listOf("c"), capturedUids.captured)
        
        coVerify(exactly = 0) { conversationManager.updatePublicKeyInfoData(any<For>()) }
    }

    @Test
    fun rust_key_data_length_exception_group_uses_for_keyed_refresh() = runTest {
        val recipient = For.Group("g1")
        val room = recipient

        
        coEvery { conversationManager.getPublicKeyInfos(room) } returns listOf(
            PublicKeyInfo(uid = "m1", identityKey = "k1", registrationId = 1, resetIdentityKeyTime = 0),
            PublicKeyInfo(uid = "m2", identityKey = "k2", registrationId = 2, resetIdentityKeyTime = 0),
        )
        every {
            messageEncryptor.encryptGroupMessage(any(), any())
        } throws RustEncryptionException(RuntimeException("KeyDataLengthException: key too short"))

        runCatching {
            sender.sendDataMessage(recipient, room, buildDataMessage(), null, admission)
        }

        
        coVerify(atLeast = 1) {
            conversationManager.updatePublicKeyInfoData(
                match<For> { it is For.Group && it.id == "g1" }
            )
        }
        
        coVerify(exactly = 0) { conversationManager.updatePublicKeyInfoData(any<List<String>>()) }
    }

    
    
    

    @Test
    fun ac4a_regression_100_member_group_1_stale_narrows_refresh_to_stale_only() = runTest {
        val recipient = For.Group("g100")
        val room = recipient
        val members = (1..100).map { "m$it" }
        
        coEvery { conversationManager.getPublicKeyInfos(room) } returns members.map { uid ->
            PublicKeyInfo(uid = uid, identityKey = "k-$uid", registrationId = 1, resetIdentityKeyTime = 0)
        }
        
        every {
            messageEncryptor.encryptGroupMessage(any(), any())
        } returns EncryptResult(
            cipherText = byteArrayOf(1),
            signedEKey = byteArrayOf(2),
            eKey = byteArrayOf(3),
            identityKey = byteArrayOf(4),
            ermKeys = members.associateWith { byteArrayOf(5) },
        )

        coEvery { messagingService.sendToGroup(any(), any()) } throws IOException("ws down")
        
        coEvery { messageSendRepository.sendMessage(any(), any()) } returnsMany listOf(
            buildStaleResponse(emptyList(), listOf("m42")),
            NewSendMessageResponse().apply { status = 0; data = NewSendMessageResponse.Data() },
        )

        val capturedUids = slot<List<String>>()
        coEvery { conversationManager.updatePublicKeyInfoData(capture(capturedUids)) } returns true

        runCatching {
            sender.sendDataMessage(recipient, room, buildDataMessage(), null, admission)
        }

        
        
        
        assertEquals(listOf("m42"), capturedUids.captured, "Expected narrow-refresh to flagged uid only")

        
        
        members.filter { it != "m42" }.forEach { otherMember ->
            assertFalse(
                capturedUids.captured.contains(otherMember),
                "Expected NO full-member refresh: $otherMember should be absent from retry uid list"
            )
        }
    }

    
    
    
    

    @Test
    fun http_fallback_retry_group_status_11001_empty_flags_uses_for_keyed_full_refresh() = runTest {
        val recipient = For.Group("g-empty")
        val room = recipient
        coEvery { conversationManager.getPublicKeyInfos(room) } returns listOf(
            PublicKeyInfo(uid = "m1", identityKey = "k1", registrationId = 1, resetIdentityKeyTime = 0)
        )
        every {
            messageEncryptor.encryptGroupMessage(any(), any())
        } returns EncryptResult(
            cipherText = byteArrayOf(1),
            signedEKey = byteArrayOf(2),
            eKey = byteArrayOf(3),
            identityKey = byteArrayOf(4),
            ermKeys = mapOf("m1" to byteArrayOf(5)),
        )
        coEvery { messagingService.sendToGroup(any(), any()) } throws IOException("ws down")
        coEvery { messageSendRepository.sendMessage(any(), any()) } returnsMany listOf(
            buildStaleResponse(emptyList(), emptyList(), status = 11001),
            NewSendMessageResponse().apply { status = 0; data = NewSendMessageResponse.Data() },
        )

        runCatching {
            sender.sendDataMessage(recipient, room, buildDataMessage(), null, admission)
        }

        
        coVerify(atLeast = 1) {
            conversationManager.updatePublicKeyInfoData(
                match<For> { it is For.Group && it.id == "g-empty" }
            )
        }
        
        
        coVerify(exactly = 0) { conversationManager.updatePublicKeyInfoData(any<List<String>>()) }
    }

    
    
    
    
    
    

    
    @Test
    fun createMessage_invalid_group_throws_permanent_not_io() = runTest {
        val recipient = For.Group("g-invalid")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns false
        coEvery { conversationManager.updatePublicKeyInfoDataResult(any()) } returns
            PublicKeyUpdateResult.EntityInvalid

        val thrown = assertFailsWith<NoValidRecipientKeysException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }
        
        
        assertFalse(
            IOException::class.java.isAssignableFrom(thrown.javaClass),
            "permanent exception must not be an IOException"
        )
    }

    





    @Test
    fun createMessage_server_empty_is_transient_io() = runTest {
        val recipient = For.Account("c")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns false
        coEvery { conversationManager.updatePublicKeyInfoDataResult(any()) } returns
            PublicKeyUpdateResult.ServerEmpty

        
        assertFailsWith<IOException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }
    }

    
    @Test
    fun createMessage_fetch_failed_throws_transient_io() = runTest {
        val recipient = For.Account("c")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns false
        coEvery { conversationManager.updatePublicKeyInfoDataResult(any()) } returns
            PublicKeyUpdateResult.FetchFailed

        
        
        
        assertFailsWith<IOException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }
    }

    
    @Test
    fun createMessage_unresolved_throws_transient_io() = runTest {
        val recipient = For.Group("g-unresolved")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns false
        coEvery { conversationManager.updatePublicKeyInfoDataResult(any()) } returns
            PublicKeyUpdateResult.Unresolved

        assertFailsWith<IOException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }
    }

    



    @Test
    fun createMessage_filtered_empty_entity_invalid_throws_permanent() = runTest {
        val recipient = For.Group("g-cleanup")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns true
        
        coEvery { conversationManager.getPublicKeyInfos(any<For>()) } returns listOf(
            PublicKeyInfo(uid = "m1", identityKey = "", registrationId = 1, resetIdentityKeyTime = 0)
        )
        coEvery { conversationManager.classifyEmptyKeys(any()) } returns
            PublicKeyUpdateResult.EntityInvalid

        assertFailsWith<NoValidRecipientKeysException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }
    }

    
    @Test
    fun createMessage_filtered_empty_classify_transient_throws_io() = runTest {
        val recipient = For.Account("c")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns true
        coEvery { conversationManager.getPublicKeyInfos(any<For>()) } returns listOf(
            PublicKeyInfo(uid = "c", identityKey = "", registrationId = 1, resetIdentityKeyTime = 0)
        )
        coEvery { conversationManager.classifyEmptyKeys(any()) } returns
            PublicKeyUpdateResult.Updated

        
        
        assertFailsWith<IOException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null, admission)
        }
    }
}
