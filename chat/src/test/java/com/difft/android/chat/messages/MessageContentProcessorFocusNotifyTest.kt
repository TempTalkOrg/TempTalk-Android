package com.difft.android.chat.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.focus.FocusSnapshot
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import com.google.gson.JsonParser
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import kotlin.test.assertEquals
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.test.fakes.TestMessagingPause

/** `notifyType == NOTIFY_MESSAGE_TYPE_FOCUS (29)` reaches [GroupFocusRepository.apply] with the parsed snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageContentProcessorFocusNotifyTest {

    private val admission = requireNotNull(TestMessagingPause().open().gate.tryAdmit(AdmissionKind.RECEIVE, "test"))

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var processor: MessageContentProcessor
    private lateinit var repository: GroupFocusRepository

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)
        val globalServicesMock: GlobalHiltEntryPoint = mockk(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns "+10000000000"

        repository = mockk(relaxed = true)
        processor = MessageContentProcessor(
            context = ApplicationProvider.getApplicationContext<Application>(),
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
            settingsApplier = mockk(relaxed = true),
            localMessageCreator = mockk(relaxed = true),
            groupCryptoRepo = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
            weakContactReconciler = mockk(relaxed = true),
            gson = Gson(),
            conversationUnreadMarker = mockk(relaxed = true),
            criticalAlertHighlightWriter = mockk(relaxed = true),
            groupFocusRepository = repository,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun focusNotify(focusJson: String?, trigger: String? = "USER"): SignalServiceDataClass {
        val data = Data(
            actionType = 0,
            focus = focusJson?.let { JsonParser.parseString(it) },
            trigger = trigger,
        )
        val notify = TTNotifyMessage(data = data, notifyTime = 1L, notifyType = TTNotifyMessage.NOTIFY_MESSAGE_TYPE_FOCUS)
        val envelope = Envelope.newBuilder().setSource("server").setSourceDevice(1).setTimestamp(100L).build()
        return SignalServiceDataClass(envelope, null, notify)
    }

    @Test
    fun `focus notify applies the parsed snapshot with its trigger`() = runTest {
        val json = """{"enabled":true,"groupIds":["gid-1"],"until":1761503600000,"othersAllowMentionMe":true,
            "othersAllowMentionAll":false,"othersAllowGroupCall":true,"foldOthers":false,"invalidReason":null,"version":7}"""

        processor.process(focusNotify(json, trigger = "INVALIDATED"), "test", admission)

        val snapshot = slot<FocusSnapshot>()
        coVerify(exactly = 1) { repository.apply(capture(snapshot), "INVALIDATED") }
        assertEquals(
            FocusSnapshot(
                enabled = true, groupIds = listOf("gid-1"), until = 1761503600000L,
                othersAllowMentionMe = true, othersAllowMentionAll = false, othersAllowGroupCall = true,
                foldOthers = false, invalidReason = null, version = 7,
            ),
            snapshot.captured,
        )
    }

    @Test
    fun `focus notify without a snapshot is skipped`() = runTest {
        processor.process(focusNotify(focusJson = null), "test", admission)
        coVerify(exactly = 0) { repository.apply(any(), any()) }
    }
}
