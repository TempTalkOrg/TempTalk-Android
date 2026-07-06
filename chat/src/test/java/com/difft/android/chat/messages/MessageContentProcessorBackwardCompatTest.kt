package com.difft.android.chat.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.google.gson.Gson
import com.google.protobuf.ByteString
import difft.android.messageserialization.model.NotifyMessage
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage

/**
 * Backward-compatibility regression test — verifies the "old version silently drops"
 * contract.
 *
 * New version sends `Content{forwardNotice=...}` (both primary and self-sync).
 * Old binaries don't know field 10 → `hasForwardNotice()` is a no-op branch they
 * never check, and every other `hasXxx()` returns false → control falls through
 * to `return null`. No crash, no DB write.
 *
 * We can't downgrade the current build, so we simulate the condition that matters
 * most: a Content / SyncMessage with none of the known branches set → processor
 * returns null cleanly. Pins the fall-through behavior as a contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageContentProcessorBackwardCompatTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Application
    private lateinit var processor: MessageContentProcessor
    private lateinit var localMessageCreator: LocalMessageCreator
    private lateinit var globalServicesMock: GlobalHiltEntryPoint

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
        } returns mockk<NotifyMessage>(relaxed = true)

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
            weakContactReconciler = mockk(relaxed = true),
            gson = Gson(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ------------------------------------------------------------------
    // Path 1: Unknown top-level Content type (simulates old version receiving
    // Content{forwardNotice=...} where forwardNotice field 10 is unknown).
    //
    // Builds an empty Content (no dataMessage, no syncMessage, no receiptMessage,
    // no callMessage, no groupKeyMessage, no notifyMessage, no forwardNotice).
    // All hasXxx() return false → handleMessage falls through → return null.
    // ------------------------------------------------------------------
    @Test
    fun `handleMessage with empty Content returns null silently — no crash`() = runTest {
        val envelope = minimalEnvelope("+15557777", 100L)
        val content = Content.newBuilder().build()

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        assertNull(
            "empty Content (no known branches) must return null — old-version silent-drop contract",
            result
        )
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ------------------------------------------------------------------
    // Path 2: Unknown SyncMessage sub-type. SyncMessage with no Sent, no Read
    // → falls through the sync inner branch → return null.
    // ------------------------------------------------------------------
    @Test
    fun `handleMessage with empty SyncMessage from myself returns null silently`() = runTest {
        val envelope = minimalEnvelope(MY_ID, 200L) // senderId == myId → passes outer check
        val content = Content.newBuilder()
            .setSyncMessage(SyncMessage.newBuilder().build())
            .build()

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        assertNull(
            "empty SyncMessage (no sent/read) must return null — " +
                    "old-version silent-drop contract for inner sync branches",
            result
        )
        coVerify(exactly = 0) {
            localMessageCreator.createForwardNoticeMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ------------------------------------------------------------------
    // Path 3: SyncMessage from a DIFFERENT user — rejected at outer senderId
    // guard (handleMessage line 119-121). Pins the "don't process foreign
    // sync messages" invariant.
    // ------------------------------------------------------------------
    @Test
    fun `handleMessage rejects SyncMessage when senderId is not myId`() = runTest {
        val otherSender = "+15558888"
        val envelope = minimalEnvelope(otherSender, 300L)
        val content = Content.newBuilder()
            .setSyncMessage(SyncMessage.newBuilder().build())
            .build()

        val result = processor.process(SignalServiceDataClass(envelope, content, null), TAG)

        assertNull(result)
    }

    private fun minimalEnvelope(source: String, ts: Long): Envelope = Envelope.newBuilder()
        .setSource(source)
        .setSourceDevice(1)
        .setTimestamp(ts)
        .setSystemShowTimestamp(ts)
        .setContent(ByteString.copyFrom(ByteArray(0)))
        .build()

    companion object {
        private const val MY_ID = "+10000000"
        private const val TAG = "TestTag"
    }
}
