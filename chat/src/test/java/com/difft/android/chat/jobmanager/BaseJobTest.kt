package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobs.BaseJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BaseJobTest {

    // -- run() success path --

    @Test
    fun `run returns success when onRun succeeds`() = runTest {
        val job = TestJob(shouldSucceed = true)

        val result = job.run()

        assertTrue(result.isSuccess())
    }

    // -- run() retry path --

    @Test
    fun `run returns retry when onRun throws and onShouldRetry returns true`() = runTest {
        val fixedBackoff = 3000L
        val job = TestJob(
            shouldSucceed = false,
            exception = IOException("network error"),
            shouldRetry = true,
            backoffOverride = fixedBackoff
        )

        val result = job.run()

        assertTrue(result.isRetry())
        assertEquals(fixedBackoff, result.getBackoffInterval())
    }

    // -- run() failure path --

    @Test
    fun `run returns failure when onRun throws and onShouldRetry returns false`() = runTest {
        val job = TestJob(
            shouldSucceed = false,
            exception = RuntimeException("fatal"),
            shouldRetry = false
        )

        val result = job.run()

        assertTrue(result.isFailure())
    }

    // -- CancellationException is rethrown, not caught --

    @Test
    fun `run rethrows CancellationException instead of treating as failure`() = runTest {
        val job = TestJob(
            shouldSucceed = false,
            exception = CancellationException("coroutine cancelled"),
            shouldRetry = true
        )

        assertFailsWith<CancellationException> {
            job.run()
        }
    }

    // -- getNextRunAttemptBackoff delegates to BackoffUtil --

    @Test
    fun `getNextRunAttemptBackoff delegates to BackoffUtil with correct params`() {
        val job = TestJob(shouldSucceed = true)

        // BackoffUtil.exponentialBackoff is a pure function with jitter in range [0.75, 1.25)
        // For pastAttemptCount=2, maxBackoff=60000: exponentialBackoff = 2^2 * 1000 = 4000
        // actualBackoff = min(4000, 60000) = 4000
        // result in [4000*0.75, 4000*1.25) = [3000, 5000)
        val backoff = job.getNextRunAttemptBackoff(2, IOException("test"))

        assertTrue(backoff in 3000..4999, "Backoff $backoff should be in range [3000, 5000)")
    }

    // -- Retry path uses correct attempt count --

    @Test
    fun `run retry path passes runAttempt + 1 to getNextRunAttemptBackoff`() = runTest {
        var capturedAttemptCount = -1
        val job = TestJob(
            shouldSucceed = false,
            exception = IOException("retry"),
            shouldRetry = true,
            onBackoffCalled = { attemptCount, _ -> capturedAttemptCount = attemptCount }
        )
        job.runAttempt = 0

        val result = job.run()

        assertTrue(result.isRetry())
        // runAttempt=0, so pastAttemptCount should be 0 + 1 = 1
        assertEquals(1, capturedAttemptCount)
    }

    @Test
    fun `run retry path uses incremented attempt count for higher attempts`() = runTest {
        var capturedAttemptCount = -1
        val job = TestJob(
            shouldSucceed = false,
            exception = IOException("retry-high"),
            shouldRetry = true,
            onBackoffCalled = { attemptCount, _ -> capturedAttemptCount = attemptCount }
        )
        job.runAttempt = 2

        val result = job.run()

        assertTrue(result.isRetry())
        // runAttempt=2, so pastAttemptCount should be 2 + 1 = 3
        assertEquals(3, capturedAttemptCount)
    }

    // -- Concrete test subclass of BaseJob --

    private class TestJob(
        private val shouldSucceed: Boolean = true,
        private val exception: Exception = RuntimeException("test exception"),
        private val shouldRetry: Boolean = false,
        private val backoffOverride: Long? = null,
        private val onBackoffCalled: ((Int, Exception) -> Unit)? = null
    ) : BaseJob(
        Job.Parameters.Builder("test-job-id")
            .setMaxAttempts(3)
            .build()
    ) {
        override fun serialize(): Data = Data.EMPTY

        override fun getFactoryKey(): String = "TestJob"

        override fun onFailure() {
            // no-op for tests
        }

        override suspend fun onRun() {
            if (!shouldSucceed) {
                throw exception
            }
        }

        override fun onShouldRetry(e: Exception): Boolean = shouldRetry

        override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long {
            onBackoffCalled?.invoke(pastAttemptCount, exception)
            return backoffOverride ?: super.getNextRunAttemptBackoff(pastAttemptCount, exception)
        }
    }
}
