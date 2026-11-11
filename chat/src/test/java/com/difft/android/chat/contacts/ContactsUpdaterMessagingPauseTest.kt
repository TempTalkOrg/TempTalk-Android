package com.difft.android.chat.contacts

import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.TTNotifyMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds






@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactsUpdaterMessagingPauseTest {

    private val userManager = mockk<UserManager>(relaxed = true)
    private val wcdb = mockk<WCDB>(relaxed = true)
    private val loopScope = TestScope(StandardTestDispatcher())

    private lateinit var updater: ContactsUpdater

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns loopScope
        every { wcdb.isDbInaccessible } returns false
        every { userManager.getUserData()?.directoryVersionForContactors } returns 0
        updater = ContactsUpdater(userManager, mockk(relaxed = true), wcdb, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        loopScope.cancel()
        unmockkAll()
    }

    @Test
    fun `a queued directory notify keeps the pause waiting until its batch is handled`() = runTest {
        val messaging = TestMessagingPause().open()
        val batchTicket = assertNotNull(messaging.gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        updater.updateBySignalNotifyMessage(notify(), batchTicket)
        batchTicket.close()

        messaging.pauseMessaging()
        val idle = async { messaging.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 30.seconds) }
        runCurrent()
        assertFalse(idle.isCompleted, "the pause must wait for the queued notify")

        loopScope.testScheduler.advanceTimeBy(3_001)
        loopScope.testScheduler.runCurrent()
        runCurrent()

        verify { userManager.getUserData() }
        assertTrue(idle.await())
    }

    private fun notify() = TTNotifyMessage(
        data = Data(actionType = 0, directoryVersion = 0),
        notifyTime = 1_382L,
        notifyType = TTNotifyMessage.NOTIFY_MESSAGE_TYPE_UPDATE_CONTACT,
    )
}
