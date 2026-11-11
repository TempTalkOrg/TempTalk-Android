package com.difft.android.chat.jobs

import android.app.Application
import app.cash.turbine.test
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


class MessagingPauseJobHoldTest {

    private val sendKeys = listOf(
        PushTextSendJob.KEY,
        PushReactionSendJob.KEY,
        PushReadReceiptSendJob.KEY,
        PushMarkAsUnreadSendJob.KEY,
        PushGroupKeySendJob.KEY,
        PushForwardNoticeSendJob.KEY,
        PushActivityNoticeSendJob.KEY,
        PushScreenShotSendJob.KEY,
        SettingSyncJob.KEY,
    )

    @Test
    fun `every registered job except attachment download is a send job`() {
        val registered = JobManagerFactories.getJobFactories(mockk<Application>()).keys
        assertEquals(registered - DownloadAttachmentJob.KEY, MessagingPauseJobHold.SEND_JOB_KEYS)
    }

    @Test
    fun `send jobs are held while loading and while paused, released when open`() {
        val pause = TestMessagingPause()
        val hold = testJobHold(pause.gate)

        sendKeys.forEach { assertTrue(hold.holds(spec(it)), "loading must hold $it") }
        pause.open()
        sendKeys.forEach { assertFalse(hold.holds(spec(it)), "open must not hold $it") }
        pause.pauseMessaging()
        sendKeys.forEach { assertTrue(hold.holds(spec(it)), "paused must hold $it") }
    }

    @Test
    fun `attachment download is never held`() {
        val pause = TestMessagingPause().open().pauseMessaging()

        assertFalse(testJobHold(pause.gate).holds(spec(DownloadAttachmentJob.KEY)))
    }

    @Test
    fun `released emits when messaging opens`() = runTest {
        val pause = TestMessagingPause()
        val hold = testJobHold(pause.gate)

        hold.released.test {
            awaitItem() 
            pause.open()
            awaitItem()
            pause.pauseMessaging()
            expectNoEvents()
            pause.resumeMessaging()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `send jobs are held while no login is signed in and released when one is`() = runTest {
        val login = MutableStateFlow<String?>(null)
        val pause = TestMessagingPause()
        val hold = MessagingPauseJobHold(pause.gate, login)

        hold.released.test {
            pause.open()
            awaitItem()
            sendKeys.forEach { assertTrue(hold.holds(spec(it)), "no login must hold $it") }
            assertFalse(hold.holds(spec(DownloadAttachmentJob.KEY)))
            assertNull(hold.current())

            login.value = TEST_LOGIN
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        sendKeys.forEach { assertFalse(hold.holds(spec(it)), "a signed-in login must not hold $it") }
        assertEquals(TEST_LOGIN, hold.current())
    }

    
    
    @Test
    fun `released emits for a login already signed in when the collector subscribes`() = runTest {
        val login = MutableStateFlow<String?>(null)
        
        val hold = MessagingPauseJobHold(TestMessagingPause().gate, login)
        login.value = TEST_LOGIN

        hold.released.test {
            awaitItem()
            login.value = "login-b"
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the first load is the login read at that load, whatever is signed in later`() = runTest {
        val login = MutableStateFlow<String?>(null)
        val firstLoad = CompletableDeferred<String?>()
        val hold = MessagingPauseJobHold(TestMessagingPause().gate, login, firstLoad)

        assertFalse(hold.loaded())
        firstLoad.complete(null)
        login.value = TEST_LOGIN

        assertTrue(hold.loaded())
        assertNull(hold.awaitFirstLoad())
    }

    private fun spec(factoryKey: String) = JobSpec(
        id = "id-$factoryKey",
        factoryKey = factoryKey,
        queueKey = null,
        createTime = 1L,
        nextRunAttemptTime = 0L,
        runAttempt = 0,
        maxAttempts = 3,
        lifespan = Job.Parameters.IMMORTAL,
        serializedData = "{}",
        isRunning = false,
        isMemoryOnly = false,
    )
}
