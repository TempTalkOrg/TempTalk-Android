package com.difft.android.chat.jobs

import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.message.LocalMessageCreator
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

/**
 * Unit tests for [PushForwardNoticeSendJob.onPushSend].
 *
 * Coverage (the 5 contract invariants):
 *
 *   1. **Group target**: `sendSyncToSelf=false` → Sender called exactly once (no sync leg).
 *   2. **1v1 to other**: `sendSyncToSelf=true` → Sender call carries the sync flag.
 *   3. **Self target (Save-to-Notes)**: `sendSyncToSelf=false` → Sender called exactly once.
 *   4. **Sender failure rethrows → Job retries**: Sender throws IOException → Job propagates
 *      → JobManager retries per PushSendJob.onShouldRetry contract. Local DB insert is NOT
 *      called (guarding the "don't insert if peer didn't get it" invariant).
 *   5. **Local DB failure does NOT rethrow**: primary send succeeded, LocalMessageCreator
 *      throws → swallowed (L.e log), the Job completes normally. Rethrowing would
 *      trigger retry → peer gets the notice twice (duplicate envelopes).
 *
 *   6. **Timestamp three-way alignment**: `sendTs` passed to Sender is the SAME value
 *      passed to LocalMessageCreator (envelope.timestamp = localDB.timestamp).
 *
 * Rule ordering per project standard: HiltAndroidRule(0) → TestDispatcherRule(1) →
 * GlobalStaticMockRule(2). We inline equivalent setup (no rules) because
 * `:base:testFixtures/kotlin/` sources are NOT published to consumer modules due to a
 * documented kapt limitation (see base/build.gradle.kts:43-45 and Task 2 deviation D2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushForwardNoticeSendJobTest {

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

        // Mock globalServices.myId (Job reads it inside onPushSend to compute sendSyncToSelf).
        mockGlobalServices = mockk(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobalServices
        every { mockGlobalServices.myId } returns myId

        // Default: Sender succeeds. Tests override as needed.
        coEvery {
            messageSender.sendForwardNoticeMessage(
                recipient = any(),
                room = any(),
                message = any(),
                sendSyncToSelf = any(),
                sendTimestamp = any()
            )
        } returns successResult()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -----------------------------------------------------------------
    // Case 1: Group target → sendSyncToSelf = false
    // -----------------------------------------------------------------
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
            gson = gson
        )

        job.onPushSend()

        coVerify(exactly = 1) {
            messageSender.sendForwardNoticeMessage(
                recipient = target,
                room = target,
                message = any(),
                sendSyncToSelf = false,
                sendTimestamp = any()
            )
        }
        // Local DB insert happened on primary success.
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

    // -----------------------------------------------------------------
    // Case 2: 1v1 to other — sendSyncToSelf = true
    // -----------------------------------------------------------------
    @Test
    fun `1v1 to other — sendSyncToSelf is true`() = runTest {
        val target = For.Account(peerId)
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.SINGLE,
            listOf("+author"),
            1
        )
        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson
        )

        job.onPushSend()

        coVerify(exactly = 1) {
            messageSender.sendForwardNoticeMessage(
                recipient = target,
                room = target,
                message = any(),
                sendSyncToSelf = true,       // KEY: other device sync leg required
                sendTimestamp = any()
            )
        }
    }

    // -----------------------------------------------------------------
    // Case 3: Self target (save-to-notes) — sendSyncToSelf = false
    // (server routes recipient=self to all my devices; no dedicated sync).
    // -----------------------------------------------------------------
    @Test
    fun `self target save-to-notes — sendSyncToSelf is false`() = runTest {
        val target = For.Account(myId)
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.SAVE_TO_NOTES,
            listOf("+author-x"),
            5
        )
        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson
        )

        job.onPushSend()

        coVerify(exactly = 1) {
            messageSender.sendForwardNoticeMessage(
                recipient = target,
                room = target,
                message = any(),
                sendSyncToSelf = false,
                sendTimestamp = any()
            )
        }
    }

    // -----------------------------------------------------------------
    // Case 4: Sender IOException rethrows → Job retries. Local DB is NOT inserted.
    // -----------------------------------------------------------------
    @Test
    fun `sender failure rethrows and local DB is not inserted`() = runTest {
        val target = For.Group(groupId)
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.COMBINED,
            listOf("+a"),
            1
        )
        coEvery {
            messageSender.sendForwardNoticeMessage(any(), any(), any(), any(), any())
        } throws IOException("simulated network failure")

        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson
        )

        assertFailsWith<IOException> { job.onPushSend() }

        // Critical invariant: DB is NOT inserted when primary send fails.
        // Otherwise the originating device would show a notice that peers never received.
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(
                any(), any(), any(), any(), any(), any()
            )
        }
    }

    // -----------------------------------------------------------------
    // Case 5: Local DB failure does NOT rethrow — peer already has the notice,
    //   retry would duplicate envelopes.
    // -----------------------------------------------------------------
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
            null, target, notice, messageSender, localMessageCreator, gson
        )

        // Must NOT throw — a throw here would propagate to PushSendJob.onRun() → JobManager
        // → retry → re-send to peer, producing duplicate envelopes.
        job.onPushSend()

        // Sender was called exactly once (no retry triggered by the Job layer).
        coVerify(exactly = 1) {
            messageSender.sendForwardNoticeMessage(any(), any(), any(), any(), any())
        }
    }

    // -----------------------------------------------------------------
    // Case 6: Timestamp three-way alignment — the SAME sendTs flows into both
    //   the Sender call (as Envelope.timestamp) and the LocalMessageCreator
    //   call (as NotifyMessage.timestamp). This is what makes
    //   generateMessageId(ts, myId, deviceId) equivalent across paths.
    // -----------------------------------------------------------------
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
                any(), any(), any(), any(), capture(senderTsCapture)
            )
        } returns successResult()
        coEvery {
            localMessageCreator.createForwardNoticeMessage(
                any(), any(), any(), any(), capture(creatorTsCapture), any()
            )
        } returns mockk<NotifyMessage>(relaxed = true)

        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson
        )

        job.onPushSend()

        // sendTs must be identical in both paths — otherwise messageId three-tuple collides.
        assertEquals(
            "Envelope.timestamp (sender) and NotifyMessage.timestamp (localDB) MUST match — " +
                "they form the messageId three-tuple alongside myId + deviceId.",
            senderTsCapture.captured,
            creatorTsCapture.captured
        )
    }

    // -----------------------------------------------------------------
    // Case 7: serialize / deserialize roundtrip produces same serialize output.
    //   Guards Gson `@SerializedName` wiring on ForwardNoticeData.Scene values.
    // -----------------------------------------------------------------
    @Test
    fun `serialize roundtrip preserves target + notice`() {
        val target = For.Group("g-1")
        val notice = ForwardNoticeData(
            ForwardNoticeData.Scene.SAVE_TO_NOTES,
            listOf("+a", "+b", "+c"),
            7
        )

        val job = PushForwardNoticeSendJob(
            null, target, notice, messageSender, localMessageCreator, gson
        )

        val data = job.serialize()
        assertEquals("g-1", data.getString("target_id"))
        assertEquals(true, data.getBooleanOrDefault("target_is_group", false))

        val reJson = data.getString("notice_data_json")!!
        val reNotice = gson.fromJson(reJson, ForwardNoticeData::class.java)
        assertEquals(notice, reNotice)
    }

    // ----- helpers -----

    private fun successResult(): SendMessageResult = SendMessageResult.success(
        address = "addr",
        needsSync = false,
        duration = 0L,
        systemShowTimestamp = 1_700_000_000_000L,
        notifySequenceId = 0L,
        sequenceId = 0L
    )
}
