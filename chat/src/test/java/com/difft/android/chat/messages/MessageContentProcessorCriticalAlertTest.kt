package com.difft.android.chat.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.call.LCallManager
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertRoomRegistry
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import difft.android.messageserialization.For
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope

/**
 * The critical-alert branch of [MessageContentProcessor.handleNotifyMessage] (#1381): an alert for
 * the call being joined or already active still gets its local chat message but does not highlight
 * the conversation (the join-during-write ordering is covered by CriticalAlertHighlightWriterTest);
 * an alert for another call does both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageContentProcessorCriticalAlertTest {

    private lateinit var processor: MessageContentProcessor
    private lateinit var notificationUtil: MessageNotificationUtil
    private lateinit var dbRoomStore: DBRoomStore
    private lateinit var localMessageCreator: LocalMessageCreator
    private val gate = CriticalAlertCallGate(OnGoingCallStateManager(), CriticalAlertRoomRegistry())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // LCallManager's <clinit> reads ApplicationHelper.instance (lateinit).
        ApplicationHelper.init(mockk<ScopeApplication>(relaxed = true))
        mockkObject(LCallManager)
        coEvery { LCallManager.getCriticalAlertNotificationContent(any(), any()) } returns ("title" to "content")

        val globalServicesMock = mockk<GlobalHiltEntryPoint>(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns MY_ID

        notificationUtil = mockk(relaxed = true)
        every { notificationUtil.isCriticalAlertTimestampValid(any()) } returns true
        dbRoomStore = mockk(relaxed = true)
        localMessageCreator = mockk(relaxed = true)

        processor = MessageContentProcessor(
            context = context,
            dbRoomStore = dbRoomStore,
            messageStore = mockk(relaxed = true),
            asyncMessageJobsManager = mockk(relaxed = true),
            contactsUpdater = mockk(relaxed = true),
            groupUpdater = mockk(relaxed = true),
            messageArchiveManager = mockk(relaxed = true),
            lCallManagerProvider = mockk(relaxed = true),
            receiptMessageHelper = mockk(relaxed = true),
            messageNotificationUtil = notificationUtil,
            conversationSettingsManager = mockk(relaxed = true),
            settingsApplier = mockk(relaxed = true),
            localMessageCreator = localMessageCreator,
            groupCryptoRepo = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
            weakContactReconciler = mockk(relaxed = true),
            gson = Gson(),
            conversationUnreadMarker = mockk(relaxed = true),
            criticalAlertHighlightWriter = CriticalAlertHighlightWriter(dbRoomStore, gate),
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `an alert for the joining call creates its message without highlighting the conversation`() = runTest {
        gate.beginJoin(ROOM_ID, PEER)

        processAlert()

        verify(exactly = 1) { notificationUtil.showCriticalAlert(For.Account(PEER), "title", "content", ALERT_TS, ROOM_ID) }
        coVerify(exactly = 1) {
            localMessageCreator.createCriticalAlertMessage(SERVER_TS, ALERT_TS, For.Account(PEER), For.Account(PEER), 1)
        }
        verify(exactly = 0) { dbRoomStore.setCriticalAlertIfUnread(any(), any()) }
    }

    @Test
    fun `an alert for another call creates its message and keeps the highlight`() = runTest {
        gate.beginJoin(OTHER_ROOM_ID, PEER)

        processAlert()

        coVerify(exactly = 1) { localMessageCreator.createCriticalAlertMessage(SERVER_TS, ALERT_TS, any(), any(), any()) }
        verify(exactly = 1) { dbRoomStore.setCriticalAlertIfUnread(PEER, SERVER_TS) }
        verify(exactly = 0) { dbRoomStore.clearCriticalAlert(any()) }
    }

    private suspend fun processAlert() {
        val envelope = Envelope.newBuilder()
            .setSource(PEER)
            .setSourceDevice(1)
            .setTimestamp(ALERT_TS)
            .setSystemShowTimestamp(SERVER_TS)
            .build()
        val data = Data(
            actionType = 0,
            conversation = JsonPrimitive(PEER),
            source = PEER,
            roomId = ROOM_ID,
            timestamp = ALERT_TS,
            showCriticalAlert = true,
            sourceDevice = 1,
        )
        val notify = TTNotifyMessage(
            data = data,
            notifyTime = 1L,
            notifyType = TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CRITICAL_ALERT_V2,
        )
        processor.process(SignalServiceDataClass(envelope, null, notify), "test")
    }

    private companion object {
        const val MY_ID = "+10000000000"
        const val PEER = "+10000000001"
        const val ROOM_ID = "room-1"
        const val OTHER_ROOM_ID = "room-2"
        const val ALERT_TS = 1_700_000_000_000L
        const val SERVER_TS = 1_700_000_000_500L
    }
}
