package com.difft.android.websocket.api

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.network.signal.MessageSendRepository
import com.difft.android.websocket.api.messages.PublicKeyInfo
import com.difft.android.websocket.api.push.exceptions.NoValidRecipientKeysException
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

/**
 * Covers the 3 retry branches in [NewSignalServiceMessageSender] that flip
 * to the uid-keyed overload per design §6.2. The WS retry branch is hardest
 * to reach from a unit test (requires the full ServiceResponseProcessor +
 * WebSocket plumbing); we exercise it via the HTTP fallback path by
 * throwing IOException from the WS call. Tests:
 * - WS retry logic (reached via IOException → HTTP fallback → 11001/stale)
 * - HTTP fallback retry narrowing
 * - Rust KeyDataLengthException branching (1v1 vs group)
 * - AC4a regression anchor (100-member group, 1 stale uid)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSignalServiceMessageSenderRetryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val messagingService: NewMessagingService = mockk(relaxed = true)
    private val messageEncryptor: INewMessageContentEncryptor = mockk(relaxed = true)
    private val conversationManager: ConversationManager = mockk(relaxed = true)
    private val messageSendRepository: MessageSendRepository = mockk(relaxed = true)

    private lateinit var sender: NewSignalServiceMessageSender

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock the top-level `globalServices` property so sender's init reading
        // `globalServices.myId` does not touch the real Hilt graph.
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

        // Default: no server-side retry signal. Tests override per-case.
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns true
        // issue #970 ②: createNewOutgoingPushMessage now reads updatePublicKeyInfoDataResult
        // (For-keyed) at :424 and classifyEmptyKeys at :442. Default both to the happy path so
        // existing retry-branch tests (which keep has=true and a non-empty key) are unaffected.
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

    // -----------------------------------------------------------------
    // HTTP fallback path retry branch
    // -----------------------------------------------------------------

    @Test
    fun http_fallback_retry_narrows_to_missing_plus_stale_plus_recipient_distinct() = runTest {
        val recipient = For.Account("c")
        val room = recipient
        val missing = listOf("a", "b")
        val stale = emptyList<String>()

        // WS path throws IOException → falls back to HTTP
        coEvery { messagingService.send(any(), any()) } throws IOException("ws down")
        // HTTP returns stale response on first, success on retry
        coEvery { messageSendRepository.sendMessage(any(), any()) } returnsMany listOf(
            buildStaleResponse(missing, stale),
            NewSendMessageResponse().apply { status = 0; data = NewSendMessageResponse.Data() },
        )

        val capturedUids = slot<List<String>>()
        coEvery { conversationManager.updatePublicKeyInfoData(capture(capturedUids)) } returns true

        runCatching {
            sender.sendDataMessage(recipient, room, buildDataMessage(), null)
        }

        // Distinct: a, b, c; c is recipient — no duplicates.
        assertEquals(setOf("a", "b", "c"), capturedUids.captured.toSet())
        assertEquals(3, capturedUids.captured.size)
        // For-keyed overload NOT invoked for the retry narrowing.
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
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null)
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
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null)
        }

        assertEquals(setOf("a", "b", "c"), capturedUids.captured.toSet())
    }

    // -----------------------------------------------------------------
    // Rust KeyDataLengthException retry branches (Round 5 C2)
    // -----------------------------------------------------------------

    @Test
    fun rust_key_data_length_exception_1v1_narrows_to_recipient_only() = runTest {
        val recipient = For.Account("c")
        // Force send path to raise a Rust key-length error.
        every { messageEncryptor.encryptOneToOneMessage(any(), any()) } throws
            RustEncryptionException(RuntimeException("KeyDataLengthException: key too short"))

        val capturedUids = slot<List<String>>()
        coEvery { conversationManager.updatePublicKeyInfoData(capture(capturedUids)) } returns true

        runCatching {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null)
        }

        assertEquals(listOf("c"), capturedUids.captured)
        // For-keyed overload NOT invoked — 1v1 path uses uid-keyed narrowing.
        coVerify(exactly = 0) { conversationManager.updatePublicKeyInfoData(any<For>()) }
    }

    @Test
    fun rust_key_data_length_exception_group_uses_for_keyed_refresh() = runTest {
        val recipient = For.Group("g1")
        val room = recipient

        // Provide enough public keys so the encryption path is invoked for the group.
        coEvery { conversationManager.getPublicKeyInfos(room) } returns listOf(
            PublicKeyInfo(uid = "m1", identityKey = "k1", registrationId = 1, resetIdentityKeyTime = 0),
            PublicKeyInfo(uid = "m2", identityKey = "k2", registrationId = 2, resetIdentityKeyTime = 0),
        )
        every {
            messageEncryptor.encryptGroupMessage(any(), any())
        } throws RustEncryptionException(RuntimeException("KeyDataLengthException: key too short"))

        runCatching {
            sender.sendDataMessage(recipient, room, buildDataMessage(), null)
        }

        // For-keyed overload invoked for group Rust retry.
        coVerify(atLeast = 1) {
            conversationManager.updatePublicKeyInfoData(
                match<For> { it is For.Group && it.id == "g1" }
            )
        }
        // uid-keyed overload NOT invoked for the Rust retry branch on groups.
        coVerify(exactly = 0) { conversationManager.updatePublicKeyInfoData(any<List<String>>()) }
    }

    // -----------------------------------------------------------------
    // AC4a regression anchor: 100-member group, 1 stale → 1 uid refresh
    // -----------------------------------------------------------------

    @Test
    fun ac4a_regression_100_member_group_1_stale_narrows_refresh_to_stale_only() = runTest {
        val recipient = For.Group("g100")
        val room = recipient
        val members = (1..100).map { "m$it" }
        // Provide public keys for the group so encryption proceeds.
        coEvery { conversationManager.getPublicKeyInfos(room) } returns members.map { uid ->
            PublicKeyInfo(uid = uid, identityKey = "k-$uid", registrationId = 1, resetIdentityKeyTime = 0)
        }
        // Group-encryption mock produces ermKeys covering every member.
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
        // HTTP first response: 1 stale uid. Second retry: success.
        coEvery { messageSendRepository.sendMessage(any(), any()) } returnsMany listOf(
            buildStaleResponse(emptyList(), listOf("m42")),
            NewSendMessageResponse().apply { status = 0; data = NewSendMessageResponse.Data() },
        )

        val capturedUids = slot<List<String>>()
        coEvery { conversationManager.updatePublicKeyInfoData(capture(capturedUids)) } returns true

        runCatching {
            sender.sendDataMessage(recipient, room, buildDataMessage(), null)
        }

        // Only the flagged stale uid should be captured. For.Group recipient.id is a gid,
        // not a real uid, so it is NOT added to the uid-keyed refresh (would be a
        // silent no-op on /keys). Critically: NONE of the other 99 members included.
        assertEquals(listOf("m42"), capturedUids.captured, "Expected narrow-refresh to flagged uid only")

        // Assert 99 other members are absent — any test regression re-widening the
        // refresh to full membership will fail here.
        members.filter { it != "m42" }.forEach { otherMember ->
            assertFalse(
                capturedUids.captured.contains(otherMember),
                "Expected NO full-member refresh: $otherMember should be absent from retry uid list"
            )
        }
    }

    // -----------------------------------------------------------------
    // Group 11001-without-flags: fall back to For-keyed full refresh
    // (regression anchor for the bug that motivated the 11001 fallback
    // fix — group id in a uid-keyed request was a silent no-op causing
    // infinite retry until RETRY_COUNT exhaustion.)
    // -----------------------------------------------------------------

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
            sender.sendDataMessage(recipient, room, buildDataMessage(), null)
        }

        // For-keyed overload invoked — facade resolves all members internally.
        coVerify(atLeast = 1) {
            conversationManager.updatePublicKeyInfoData(
                match<For> { it is For.Group && it.id == "g-empty" }
            )
        }
        // uid-keyed overload NOT invoked with [gid] — that would be a silent no-op
        // because group ids are not valid uids in the /keys service.
        coVerify(exactly = 0) { conversationManager.updatePublicKeyInfoData(any<List<String>>()) }
    }

    // -----------------------------------------------------------------
    // issue #970 ②: createNewOutgoingPushMessage permanent vs transient
    // routing. Two assertions must never cross-misfire:
    //   invalid group → NoValidRecipientKeysException (permanent, onShouldRetry=false)
    //   server-empty / weak-net / not-synced → IOException (transient, onShouldRetry=true)
    // -----------------------------------------------------------------

    /** T11: invalid group (EntityInvalid) at :424 → NoValidRecipientKeysException (NOT IOException). */
    @Test
    fun createMessage_invalid_group_throws_permanent_not_io() = runTest {
        val recipient = For.Group("g-invalid")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns false
        coEvery { conversationManager.updatePublicKeyInfoDataResult(any()) } returns
            PublicKeyUpdateResult.EntityInvalid

        val thrown = assertFailsWith<NoValidRecipientKeysException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null)
        }
        // Critical: NOT an IOException (would be re-tried forever by PushSendJob.onShouldRetry).
        // Reflective check (not `is`) so the assertion can't be statically folded away.
        assertFalse(
            IOException::class.java.isAssignableFrom(thrown.javaClass),
            "permanent exception must not be an IOException"
        )
    }

    /**
     * T11b: ServerEmpty (HTTP200 + non-null but empty keys array) at :424 → IOException (transient).
     * An empty array is an AMBIGUOUS signal (entity gone vs valid recipient whose keys have not yet
     * propagated), so it must stay retryable — only EntityInvalid (group status != 0) is permanent.
     * Guards the PR #973 code-review fix against dropping recoverable messages/group-keys.
     */
    @Test
    fun createMessage_server_empty_is_transient_io() = runTest {
        val recipient = For.Account("c")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns false
        coEvery { conversationManager.updatePublicKeyInfoDataResult(any()) } returns
            PublicKeyUpdateResult.ServerEmpty

        // IOException, NOT NoValidRecipientKeysException (which is not an IOException subtype).
        assertFailsWith<IOException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null)
        }
    }

    /** T12: weak network (FetchFailed) at :424 → IOException (retryable). */
    @Test
    fun createMessage_fetch_failed_throws_transient_io() = runTest {
        val recipient = For.Account("c")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns false
        coEvery { conversationManager.updatePublicKeyInfoDataResult(any()) } returns
            PublicKeyUpdateResult.FetchFailed

        // assertFailsWith<IOException> proves the transient type. Since
        // NoValidRecipientKeysException is NOT an IOException subtype, catching IOException here
        // already guarantees it is not the permanent type — weak net stays retryable.
        assertFailsWith<IOException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null)
        }
    }

    /** T12b: unresolved group (Unresolved) at :424 → IOException (retryable). */
    @Test
    fun createMessage_unresolved_throws_transient_io() = runTest {
        val recipient = For.Group("g-unresolved")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns false
        coEvery { conversationManager.updatePublicKeyInfoDataResult(any()) } returns
            PublicKeyUpdateResult.Unresolved

        assertFailsWith<IOException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null)
        }
    }

    /**
     * :442 path — has=true (cache claims present) but filtered-empty + entity invalid →
     * classifyEmptyKeys=EntityInvalid → NoValidRecipientKeysException.
     */
    @Test
    fun createMessage_filtered_empty_entity_invalid_throws_permanent() = runTest {
        val recipient = For.Group("g-cleanup")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns true
        // All keys filtered out (blank identityKey) → :442 branch.
        coEvery { conversationManager.getPublicKeyInfos(any<For>()) } returns listOf(
            PublicKeyInfo(uid = "m1", identityKey = "", registrationId = 1, resetIdentityKeyTime = 0)
        )
        coEvery { conversationManager.classifyEmptyKeys(any()) } returns
            PublicKeyUpdateResult.EntityInvalid

        assertFailsWith<NoValidRecipientKeysException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null)
        }
    }

    /** :442 path — filtered-empty but classify=Updated (transient self-heal) → IOException. */
    @Test
    fun createMessage_filtered_empty_classify_transient_throws_io() = runTest {
        val recipient = For.Account("c")
        coEvery { conversationManager.hasPublicKeyInfoData(any()) } returns true
        coEvery { conversationManager.getPublicKeyInfos(any<For>()) } returns listOf(
            PublicKeyInfo(uid = "c", identityKey = "", registrationId = 1, resetIdentityKeyTime = 0)
        )
        coEvery { conversationManager.classifyEmptyKeys(any()) } returns
            PublicKeyUpdateResult.Updated

        // assertFailsWith<IOException> proves transient; permanent type is excluded by the
        // type hierarchy (NoValidRecipientKeysException !is IOException).
        assertFailsWith<IOException> {
            sender.sendDataMessage(recipient, recipient, buildDataMessage(), null)
        }
    }
}
