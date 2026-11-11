package com.difft.android.chat.group

import android.content.Context
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.utils.appScope
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.GroupNotifyDetailType
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import difft.android.messageserialization.For
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds











@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupUpdaterMessagingPauseTest {

    private companion object {
        const val GROUP_ID = "group-1382"
        const val CURRENT_VERSION = 4
    }

    private val context = mockk<Context>(relaxed = true)
    private val wcdb = mockk<WCDB>(relaxed = true)
    private val dbMessageStore = mockk<DBMessageStore>(relaxed = true)
    private val loopScope = TestScope(StandardTestDispatcher())
    private var storedNotifies = 0

    private val groupUtil = mockk<GroupUtil>(relaxed = true)
    private lateinit var updater: GroupUpdater

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns loopScope
        mockkObject(ContactorUtil)
        coEvery { ContactorUtil.getContactWithID(any(), any()) } returns Optional.empty()
        every { wcdb.isDbInaccessible } returns false
        every { context.getString(any()) } returns "group crypto upgraded"
        every { dbMessageStore.putWhenNonExist(*anyVararg()) } answers {
            storedNotifies++
            1
        }
        updater = GroupUpdater(
            context = context,
            messageArchiveManager = mockk(relaxed = true),
            conversationSettingsManager = mockk(relaxed = true),
            gson = Gson(),
            dbMessageStore = dbMessageStore,
            wcdb = wcdb,
            groupUtil = groupUtil,
            groupCryptoRepo = mockk(relaxed = true),
            groupRepo = mockk(relaxed = true),
            httpClient = mockk(relaxed = true),
            groupMemberWriter = mockk(relaxed = true),
        )
        seedVersionCache()
    }

    @After
    fun tearDown() {
        loopScope.cancel()
        unmockkAll()
    }

    @Test
    fun `notify clears immediately except upgrade and invalid types`() = runTest {
        val admission = assertNotNull(TestMessagingPause().open().gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        try {
            for (type in listOf(48, 999, 6, 49, 28)) {
                val notification = notify().copy(data = notify().data?.copy(groupNotifyDetailedType = type))
                updater.handleGroupNotifyMessage(notification, wrapper(), admission)
            }
            coVerify(exactly = 1) { groupUtil.clearExpiry(GROUP_ID, "notify:6") }
            coVerify(exactly = 1) { groupUtil.clearExpiry(GROUP_ID, "notify:49") }
            coVerify(exactly = 1) { groupUtil.clearExpiry(GROUP_ID, "notify:28") }
            coVerify(exactly = 3) { groupUtil.clearExpiry(any(), any()) }
        } finally {
            admission.close()
            every { wcdb.isDbInaccessible } returns true
            loopScope.testScheduler.advanceTimeBy(3_001)
            loopScope.testScheduler.runCurrent()
        }
    }

    @Test
    fun `a queued group notify keeps the pause waiting until its notify message is stored`() = runTest {
        val messaging = TestMessagingPause().open()
        val batchTicket = assertNotNull(messaging.gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        updater.handleGroupNotifyMessage(notify(), wrapper(), batchTicket)
        
        batchTicket.close()

        messaging.pauseMessaging()
        val idle = async { messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 30.seconds) }
        runCurrent()
        assertFalse(idle.isCompleted, "the pause must wait for the queued notify")
        assertEquals(0, storedNotifies)

        loopScope.testScheduler.advanceTimeBy(3_001)
        loopScope.testScheduler.runCurrent()
        runCurrent()

        assertEquals(1, storedNotifies, "the notify message is stored by the queue consumer")
        assertTrue(idle.await())
    }

    @Test
    fun `queued notifies release their tickets when the consumer stops on an inaccessible database`() = runTest {
        val messaging = TestMessagingPause().open()
        val batchTicket = assertNotNull(messaging.gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        updater.handleGroupNotifyMessage(notify(), wrapper(), batchTicket)
        batchTicket.close()
        every { wcdb.isDbInaccessible } returns true

        loopScope.testScheduler.advanceTimeBy(3_001)
        loopScope.testScheduler.runCurrent()

        messaging.pauseMessaging()
        assertTrue(messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 1.seconds))
        assertEquals(0, storedNotifies)
    }

    private fun notify() = TTNotifyMessage(
        data = Data(
            actionType = 0,
            groupNotifyDetailedType = GroupNotifyDetailType.UpgradeGroupCrypto.value,
            groupVersion = CURRENT_VERSION + 1,
            operator = "operator-1382",
        ),
        notifyTime = 1_382L,
        notifyType = TTNotifyMessage.NOTIFY_MESSAGE_TYPE_GROUP,
        display = 1,
    )

    private fun wrapper(): SignalServiceDataClass = mockk(relaxed = true) {
        every { myId } returns "me"
        every { conversation } returns For.Group(GROUP_ID)
        every { signalServiceEnvelope } returns Envelope.newBuilder().setTimestamp(1_382L).setSourceDevice(1).build()
        every { sequenceId } returns 0L
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedVersionCache() {
        val field = GroupUpdater::class.java.getDeclaredField("groupVersionCache")
        field.isAccessible = true
        (field.get(updater) as MutableMap<String, Int>)[GROUP_ID] = CURRENT_VERSION
    }
}
