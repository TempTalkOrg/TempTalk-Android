package com.difft.android.chat.message

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.MessageActivityNoticeData
import difft.android.messageserialization.model.NotifyMessage
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.wcdb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale











@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalMessageCreatorForwardNoticeTest {

    @RelaxedMockK
    lateinit var messageStore: MessageStore

    @MockK
    lateinit var messageArchiveManager: MessageArchiveManager

    private lateinit var context: Application
    private lateinit var creator: LocalMessageCreator
    private lateinit var mockGlobalServices: GlobalHiltEntryPoint

    private val testDispatcher = UnconfinedTestDispatcher()

    private val operatorOther = "+20001"
    private val authorAlice = "+30001"
    private val authorBob = "+30002"

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        context = ApplicationProvider.getApplicationContext()
        
        val config = context.resources.configuration
        config.setLocale(Locale.ENGLISH)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        
        Dispatchers.setMain(testDispatcher)

        
        
        
        mockGlobalServices = mockk(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobalServices
        every { mockGlobalServices.myId } returns MY_ID

        
        coEvery { messageArchiveManager.getMessageArchiveTime(any(), any()) } returns 0L

        
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        every { wcdb.getContactorsFromAllTable(any(), any()) } returns emptyList()

        creator = LocalMessageCreator(context, messageStore, messageArchiveManager, Gson())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }
    
    @Test
    fun `createForwardNoticeMessage persists a NotifyMessage for the given conversation`() = runTest {
        val forWhat = For.Group("group-1")
        val timestamp = 1_700_000_000_000L
        val notice = ForwardNoticeData(
            scene = ForwardNoticeData.Scene.COMBINED,
            sourceAuthorIds = listOf(authorAlice, authorBob),
            messageCount = 5
        )
        val captured = slot<Message>()
        every { messageStore.putWhenNonExist(capture(captured)) } returns 1

        val result = creator.createForwardNoticeMessage(
            operatorId = operatorOther,
            forWhat = forWhat,
            noticeData = notice,
            systemShowTimestamp = timestamp,
            timestamp = timestamp,
            sourceDevice = DEFAULT_DEVICE_ID
        )

        
        verify(exactly = 1) { messageStore.putWhenNonExist(any<Message>()) }
        
        assertTrue("stored message must be NotifyMessage", captured.captured is NotifyMessage)
        assertSame("returned NotifyMessage must match the stored one", result, captured.captured)

        val stored = captured.captured as NotifyMessage
        assertEquals(forWhat, stored.forWhat)
        assertEquals(For.Account(operatorOther), stored.fromWho)
        assertEquals(timestamp, stored.timeStamp)
        assertEquals(timestamp, stored.systemShowTimestamp)
    }

    
    
    @Test
    fun `createForwardNoticeMessage stores NotifyMessage type so unread query excludes it`() = runTest {
        val captured = slot<Message>()
        every { messageStore.putWhenNonExist(capture(captured)) } returns 1

        creator.createForwardNoticeMessage(
            operatorId = MY_ID,
            forWhat = For.Account(operatorOther),
            noticeData = ForwardNoticeData(ForwardNoticeData.Scene.SINGLE, listOf(authorAlice), 1),
            systemShowTimestamp = 1L,
            timestamp = 1L
        )

        
        
        assertTrue(
            "createForwardNoticeMessage MUST produce a NotifyMessage so the " +
                "WCDB unread query in updateRoomUnreadState (which filters out " +
                "TYPE_NOTIFY) excludes it. A TextMessage here would silently " +
                "start incrementing unread counts.",
            captured.captured is NotifyMessage
        )
    }
    
    @Test
    fun `messageId format follows generateMessageId three-tuple`() = runTest {
        val captured = slot<Message>()
        every { messageStore.putWhenNonExist(capture(captured)) } returns 1

        val timestamp = 1_700_000_000_123L
        val deviceId = 1
        creator.createForwardNoticeMessage(
            operatorId = operatorOther,
            forWhat = For.Group("g"),
            noticeData = ForwardNoticeData(ForwardNoticeData.Scene.ONE_BY_ONE, listOf(authorAlice), 2),
            systemShowTimestamp = timestamp,
            timestamp = timestamp,
            sourceDevice = deviceId
        )

        val expectedId = LocalMessageCreator.generateMessageId(timestamp, operatorOther, deviceId)
        assertEquals(expectedId, captured.captured.id)
    }

    
    
    @Test
    fun `duplicate invocations produce identical messageId — idempotent dedup key`() = runTest {
        val stored = mutableListOf<Message>()
        every { messageStore.putWhenNonExist(capture(stored)) } returns 1

        val notice = ForwardNoticeData(ForwardNoticeData.Scene.SINGLE, listOf(authorAlice), 1)
        val timestamp = 1_700_000_000_999L
        val common: suspend () -> Unit = {
            creator.createForwardNoticeMessage(
                operatorId = operatorOther,
                forWhat = For.Group("g"),
                noticeData = notice,
                systemShowTimestamp = timestamp,
                timestamp = timestamp,
                sourceDevice = DEFAULT_DEVICE_ID
            )
        }
        common()
        common()

        
        assertEquals(2, stored.size)
        assertEquals(stored[0].id, stored[1].id)
    }
    
    
    @Test
    fun `showContent is rendered with self operator as You and authors listed`() = runTest {
        val captured = slot<Message>()
        every { messageStore.putWhenNonExist(capture(captured)) } returns 1

        creator.createForwardNoticeMessage(
            operatorId = MY_ID,
            forWhat = For.Account(operatorOther),
            noticeData = ForwardNoticeData(
                scene = ForwardNoticeData.Scene.COMBINED,
                sourceAuthorIds = listOf(authorAlice, authorBob),
                messageCount = 3
            ),
            systemShowTimestamp = 1L,
            timestamp = 1L
        )

        val notifyMsg = captured.captured as NotifyMessage
        val ttNotify = Gson().fromJson(notifyMsg.notifyContent, JsonObject::class.java)
        val showContent = ttNotify["showContent"].asString

        
        assertTrue("should start with \"You\" quoted", showContent.startsWith("\"You\""))
        assertTrue("should include message count", showContent.contains("3 messages"))
        
        
        
        assertTrue("should say 'forwarded'", showContent.contains("forwarded"))

        
        assertEquals(
            TTNotifyMessage.NOTIFY_ACTION_TYPE_FORWARD_NOTICE,
            ttNotify["notifyType"].asInt
        )
    }
    
    
    
    
    
    @Test
    fun `serialized notice JSON contains the substring the preview-exclusion LIKE filter matches`() = runTest {
        val stored = mutableListOf<Message>()
        every { messageStore.putWhenNonExist(capture(stored)) } returns 1

        creator.createForwardNoticeMessage(
            operatorId = MY_ID,
            forWhat = For.Group("g"),
            noticeData = ForwardNoticeData(ForwardNoticeData.Scene.SINGLE, listOf(authorAlice), 1),
            systemShowTimestamp = 1L,
            timestamp = 1L
        )
        creator.createActivityNoticeMessage(
            operatorId = MY_ID,
            forWhat = For.Group("g"),
            noticeData = MessageActivityNoticeData(
                type = MessageActivityNoticeData.Type.COPY,
                sourceAuthorIds = listOf(authorAlice),
                messageCount = 1
            ),
            systemShowTimestamp = 2L,
            timestamp = 2L
        )

        val forwardJson = (stored[0] as NotifyMessage).notifyContent
        val copyJson = (stored[1] as NotifyMessage).notifyContent
        assertTrue(
            "forward notice JSON must contain \"notifyType\":10020, — the exact substring " +
                "WCDBUpdateService's preview-exclusion LIKE filter matches",
            forwardJson.contains("\"notifyType\":10020,")
        )
        assertTrue(
            "copy notice JSON must contain \"notifyType\":10021, — the exact substring " +
                "WCDBUpdateService's preview-exclusion LIKE filter matches",
            copyJson.contains("\"notifyType\":10021,")
        )
    }

    companion object {
        private const val MY_ID = "ME"
    }
}
