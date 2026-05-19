package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.util.TextSecurePreferences
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class JobManagerInitTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobStorage = mockk<JobStorage>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(TextSecurePreferences::class)
        every { TextSecurePreferences.setJobManagerVersion(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkStatic(TextSecurePreferences::class)
        clearMocks(application, jobStorage)
    }

    private fun buildConfiguration(): JobManager.Configuration {
        return JobManager.Configuration.Builder()
            .setJobStorage(jobStorage)
            .setJobFactories(emptyMap())
            .setConstraintFactories(emptyMap())
            .setConstraintObservers(emptyList())
            .build()
    }

    @Test
    fun `operations wait until init completes then execute`() {
        val initBlocker = CountDownLatch(1)
        val flushCompleted = CountDownLatch(1)

        every { jobStorage.init() } answers {
            initBlocker.await()
        }

        val jobManager = JobManager(application, buildConfiguration())

        // flush() launches on managementDispatcher which awaits initDeferred
        // Run flush on a separate thread since it blocks
        Thread {
            jobManager.flush()
            flushCompleted.countDown()
        }.start()

        // Verify: flush has NOT completed yet (init is blocked)
        val completedBeforeInit = flushCompleted.await(300, TimeUnit.MILLISECONDS)
        assertEquals(false, completedBeforeInit, "flush() should not complete before init")

        // Unblock init
        initBlocker.countDown()

        // Verify: flush completes after init
        val completedAfterInit = flushCompleted.await(5, TimeUnit.SECONDS)
        assertTrue(completedAfterInit, "flush() should complete after init")
    }

    @Test
    fun `init failure still unblocks operations`() {
        every { jobStorage.init() } throws RuntimeException("Init failed")

        val flushCompleted = CountDownLatch(1)
        val jobManager = JobManager(application, buildConfiguration())

        // flush() should complete even though init threw an exception
        Thread {
            jobManager.flush()
            flushCompleted.countDown()
        }.start()

        val completed = flushCompleted.await(5, TimeUnit.SECONDS)
        assertTrue(completed, "Operations should unblock even when init fails")
    }

    @Test
    fun `getDebugInfo returns result after init`() {
        val jobManager = JobManager(application, buildConfiguration())

        // Wait for init to complete
        jobManager.flush()

        // getDebugInfo should return a result without timing out
        val result = jobManager.getDebugInfo()
        assertTrue(result.isNotEmpty(), "getDebugInfo should return a non-empty string")
        assertTrue(
            !result.contains("Timed out"),
            "getDebugInfo should not time out when init is complete"
        )
    }
}
