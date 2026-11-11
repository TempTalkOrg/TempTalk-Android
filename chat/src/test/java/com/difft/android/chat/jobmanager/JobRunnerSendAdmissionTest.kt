package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.jobs.ProbeSendJob
import com.difft.android.chat.jobs.TEST_LOGIN
import com.difft.android.chat.jobs.bindLogin
import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds









@OptIn(ExperimentalCoroutinesApi::class)
class JobRunnerSendAdmissionTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobController = mockk<JobController>(relaxed = true)
    private val pause = TestMessagingPause()

    private lateinit var runnerScope: CoroutineScope

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.chat.util.WakeLockUtil")
        every { com.difft.android.chat.util.WakeLockUtil.acquire(any(), any(), any(), any()) } returns null
        every { com.difft.android.chat.util.WakeLockUtil.release(any(), any()) } returns Unit
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockk<GlobalHiltEntryPoint>(relaxed = true)
        runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        runnerScope.cancel()
        unmockkAll()
        clearMocks(jobController)
    }

    @Test
    fun `a refused send is deferred on every run and never fails on its last attempt`() = runBlocking {
        pause.open().pauseMessaging()
        
        val job = ProbeSendJob(pause.gate, maxAttempts = 3).apply { runAttempt = 2 }
        val parked = serve(List(DEFERRALS) { job })

        launchRunner()
        withTimeout(AWAIT_MILLIS) { parked.await() }

        coVerify(exactly = DEFERRALS) { jobController.onDeferred(job) }
        coVerify(exactly = 0) { jobController.onFailure(any()) }
        coVerify(exactly = 0) { jobController.onRetry(any(), any()) }
        assertEquals(0, job.sends)
        assertEquals(2, job.runAttempt)
    }

    @Test
    fun `the pause waits for an admitted send until its result is recorded`() = runBlocking {
        pause.open()
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = ProbeSendJob(pause.gate) {
            admitted.complete(Unit)
            release.await()
        }
        lateinit var idle: Deferred<Boolean>
        var idleWhenRecorded: Boolean? = null
        every { jobController.onSuccess(job) } answers { idleWhenRecorded = idle.isCompleted }
        val parked = serve(listOf(job))

        launchRunner()
        withTimeout(AWAIT_MILLIS) { admitted.await() }
        pause.pauseMessaging()
        idle = async(Dispatchers.Default) { pause.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 5.seconds) }

        delay(SETTLE_MILLIS)
        assertFalse(idle.isCompleted, "the pause must wait for the admitted send")

        release.complete(Unit)
        assertTrue(withTimeout(AWAIT_MILLIS) { idle.await() })
        withTimeout(AWAIT_MILLIS) { parked.await() }
        assertEquals(false, idleWhenRecorded, "the result must be recorded while the ticket is held")
        assertTrue(assertNotNull(job.lastTicket).isClosed)
    }

    @Test
    fun `a ticket is closed when the send throws`() = runBlocking {
        pause.open()
        val job = ProbeSendJob(pause.gate) { error("boom") }
        val parked = serve(listOf(job))

        launchRunner()
        withTimeout(AWAIT_MILLIS) { parked.await() }

        coVerify(exactly = 1) { jobController.onFailure(job) }
        assertTrue(assertNotNull(job.lastTicket).isClosed)
        pause.pauseMessaging()
        assertTrue(pause.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 1.seconds))
    }

    @Test
    fun `a send of another login fails through onFailure without starting its work`() = runBlocking {
        pause.open()
        val job = ProbeSendJob(pause.gate).bindLogin(creator = "login-b", current = TEST_LOGIN)
        val parked = serve(listOf(job))

        launchRunner()
        withTimeout(AWAIT_MILLIS) { parked.await() }

        coVerify(exactly = 1) { jobController.onFailure(job) }
        coVerify(exactly = 0) { jobController.onRetry(any(), any()) }
        coVerify(exactly = 0) { jobController.onDeferred(any()) }
        assertEquals(1, job.failures, "onFailure marks the message failed")
        assertFalse(job.started, "no work, an upload included, may start for another login")
        assertEquals(0, job.sends)
        pause.pauseMessaging()
        assertTrue(pause.gate.closeAndAwaitIdle(TestMessagingPause.OWNER, 1.seconds), "the refusal's ticket must be closed")
    }

    

    private fun launchRunner() {
        JobRunner(application, 1, jobController, JobPredicate.NONE, UnconfinedTestDispatcher())
            .launchIn(runnerScope)
    }

    
    private fun serve(jobs: List<Job>): CompletableDeferred<Unit> {
        val queue = ArrayDeque(jobs)
        val parked = CompletableDeferred<Unit>()
        coEvery { jobController.pullNextEligibleJobForExecution(any(), any()) } coAnswers {
            queue.removeFirstOrNull() ?: run {
                parked.complete(Unit)
                awaitCancellation()
            }
        }
        return parked
    }

    private companion object {
        const val DEFERRALS = 5
        const val AWAIT_MILLIS = 5_000L
        const val SETTLE_MILLIS = 200L
    }
}
