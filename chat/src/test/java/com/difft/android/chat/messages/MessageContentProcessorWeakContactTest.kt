package com.difft.android.chat.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.contacts.WeakContactReconciler
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import io.mockk.MockKAnnotations
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
import org.difft.app.database.models.ContactorModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import kotlin.test.assertEquals

/**
 * Integration tests for the `notifyType == NOTIFY_MESSAGE_TYPE_WEAK_CONTACT (25)` dispatch branch
 * of [MessageContentProcessor.handleNotifyMessage].
 *
 * Verifies that the dispatch reads the **already-parsed** `message.data` fields directly (no
 * rawDataJson second parse) and routes to [WeakContactReconciler]:
 * - changeType=0 → `enterWeak(uid, expireAt, reason, serverTimestamp, snapshot[name/avatar])`
 * - changeType=1 → `removeWeak(uid)`
 * - missing/blank uid → warn + skip (no reconciler call)
 *
 * Reuses the [MessageContentProcessor] construction harness from the ForwardNotice tests; the
 * reconciler is a verifiable MockK fake. The weak-contact branch does NOT evaluate
 * `content.conversation`, so a minimal envelope + null Content + the [TTNotifyMessage] as the
 * 3rd `SignalServiceDataClass` arg routes straight into `handleNotifyMessage`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageContentProcessorWeakContactTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Application
    private lateinit var processor: MessageContentProcessor
    private lateinit var weakContactReconciler: WeakContactReconciler
    private lateinit var globalServicesMock: GlobalHiltEntryPoint

    private val MY_ID = "+10000000000"
    private val TAG = "test"

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        context = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(testDispatcher)

        globalServicesMock = mockk(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns MY_ID

        weakContactReconciler = mockk(relaxed = true)

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
            localMessageCreator = mockk(relaxed = true),
            groupCryptoRepo = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
            weakContactReconciler = weakContactReconciler,
            gson = Gson(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun minimalEnvelope(source: String, ts: Long): Envelope =
        Envelope.newBuilder()
            .setSource(source)
            .setSourceDevice(1)
            .setTimestamp(ts)
            .build()

    private fun weakData(
        uid: String?,
        changeType: Int,
        name: String? = null,
        avatar: String? = null,
        expireAt: Long = 0L,
        reason: Int = 0,
        serverTimestamp: Long = 0L,
        deleteTime: Long = 0L,
    ): Data = Data(actionType = 0, serverTimestamp = serverTimestamp).apply {
        // uid/name/avatar/expireTime/reason/changeType/deleteTime are vars on Data (notifyType=25 fields).
        this.uid = uid
        this.changeType = changeType
        this.name = name
        this.avatar = avatar
        this.expireTime = expireAt
        this.reason = reason
        this.deleteTime = deleteTime
    }

    private fun notify(data: Data): TTNotifyMessage =
        TTNotifyMessage(
            data = data,
            notifyTime = 1L,
            notifyType = TTNotifyMessage.NOTIFY_MESSAGE_TYPE_WEAK_CONTACT,
        )

    // ---- changeType=0 → enterWeak with parsed fields ------------------------------------

    @Test
    fun `T6 changeType 0 dispatches enterWeak with parsed data fields (deleteTime falls back to serverTimestamp)`() = runTest {
        // deleteTime omitted (0) → enterWeak's deleteTime arg falls back to serverTimestamp (12_345L).
        val data = weakData(
            uid = "peer-1",
            changeType = 0,
            name = "Server Name",
            avatar = """{"attachmentId":"a1"}""",
            expireAt = 99_000L,
            reason = 0,
            serverTimestamp = 12_345L,
        )
        val envelope = minimalEnvelope("peer-1", 100L)

        processor.process(SignalServiceDataClass(envelope, null, notify(data)), TAG)

        val snapSlot = slot<ContactorModel>()
        coVerify(exactly = 1) {
            weakContactReconciler.enterWeak("peer-1", 99_000L, 0, 12_345L, capture(snapSlot))
        }
        // Snapshot carries the server-supplied name/avatar (no rawDataJson second parse).
        assertEquals("peer-1", snapSlot.captured.id)
        assertEquals("Server Name", snapSlot.captured.name)
        assertEquals("""{"attachmentId":"a1"}""", snapSlot.captured.avatar)
        coVerify(exactly = 0) { weakContactReconciler.removeWeak(any()) }
    }

    // ---- deleteTime: explicit server value preferred; 0 falls back to serverTimestamp ----

    @Test
    fun `T6b changeType 0 uses explicit deleteTime when server provides it`() = runTest {
        // deleteTime > 0 → used directly as enterWeak's deleteTime arg, NOT serverTimestamp.
        val data = weakData(
            uid = "peer-1b",
            changeType = 0,
            expireAt = 99_000L,
            serverTimestamp = 12_345L,
            deleteTime = 8_888L,
        )
        val envelope = minimalEnvelope("peer-1b", 110L)

        processor.process(SignalServiceDataClass(envelope, null, notify(data)), TAG)

        coVerify(exactly = 1) {
            weakContactReconciler.enterWeak("peer-1b", 99_000L, 0, 8_888L, any())
        }
    }

    // ---- changeType=1 → removeWeak ------------------------------------------------------

    @Test
    fun `T6 changeType 1 dispatches removeWeak`() = runTest {
        val data = weakData(uid = "peer-2", changeType = 1, serverTimestamp = 7_000L)
        val envelope = minimalEnvelope("peer-2", 200L)

        processor.process(SignalServiceDataClass(envelope, null, notify(data)), TAG)

        coVerify(exactly = 1) { weakContactReconciler.removeWeak("peer-2") }
        coVerify(exactly = 0) { weakContactReconciler.enterWeak(any(), any(), any(), any(), any()) }
    }

    // ---- missing uid → warn + skip, no reconciler call ----------------------------------

    @Test
    fun `T6 missing uid skips reconciler entirely`() = runTest {
        val data = weakData(uid = null, changeType = 0, serverTimestamp = 1_000L)
        val envelope = minimalEnvelope("peer-3", 300L)

        processor.process(SignalServiceDataClass(envelope, null, notify(data)), TAG)

        coVerify(exactly = 0) { weakContactReconciler.enterWeak(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { weakContactReconciler.removeWeak(any()) }
    }

    // ---- unknown changeType → no enter/remove -------------------------------------------

    @Test
    fun `T6 unknown changeType does not call enter or remove`() = runTest {
        val data = weakData(uid = "peer-4", changeType = 9, serverTimestamp = 1_000L)
        val envelope = minimalEnvelope("peer-4", 400L)

        processor.process(SignalServiceDataClass(envelope, null, notify(data)), TAG)

        coVerify(exactly = 0) { weakContactReconciler.enterWeak(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { weakContactReconciler.removeWeak(any()) }
    }
}
