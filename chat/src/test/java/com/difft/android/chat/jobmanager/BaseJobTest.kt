package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobs.BaseJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaseJobTest {

    

    @Test
    fun `run returns success when onRun succeeds`() = runTest {
        val job = TestJob(shouldSucceed = true)

        val result = job.run()

        assertTrue(result.isSuccess())
    }

    

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

    

    @Test
    fun `run treats stray TimeoutCancellationException as retry when onShouldRetry is true`() = runTest {
        val job = TestJob(
            shouldSucceed = false,
            exception = strayTimeout(),
            shouldRetry = true,
            backoffOverride = 1234L
        )

        val result = job.run()

        assertTrue(result.isRetry())
        assertEquals(1234L, result.getBackoffInterval())
    }

    @Test
    fun `run treats stray CancellationException as failure when onShouldRetry is false`() = runTest {
        val job = TestJob(
            shouldSucceed = false,
            exception = CancellationException("stray"),
            shouldRetry = false
        )

        val result = job.run()

        assertTrue(result.isFailure())
    }

    

    @Test
    fun `run rethrows CancellationException when the calling coroutine is cancelled`() = runTest {
        val job = TestJob(
            shouldSucceed = false,
            exception = CancellationException("coroutine cancelled"),
            shouldRetry = true,
            onRunBlock = { currentCoroutineContext().cancel() }
        )
        var outcome: kotlin.Result<Job.Result>? = null

        launch(start = CoroutineStart.UNDISPATCHED) {
            outcome = runCatching { job.run() }
        }.join()

        assertTrue(outcome?.exceptionOrNull() is CancellationException, "was $outcome")
    }

    private suspend fun strayTimeout(): TimeoutCancellationException =
        try {
            withTimeout(1) { delay(100) }
            error("unreachable")
        } catch (e: TimeoutCancellationException) {
            e
        }

    

    @Test
    fun `getNextRunAttemptBackoff delegates to BackoffUtil with correct params`() {
        val job = TestJob(shouldSucceed = true)

        
        
        
        
        val backoff = job.getNextRunAttemptBackoff(2, IOException("test"))

        assertTrue(backoff in 3000..4999, "Backoff $backoff should be in range [3000, 5000)")
    }

    

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
        
        assertEquals(3, capturedAttemptCount)
    }

    

    private class TestJob(
        private val shouldSucceed: Boolean = true,
        private val exception: Exception = RuntimeException("test exception"),
        private val shouldRetry: Boolean = false,
        private val backoffOverride: Long? = null,
        private val onBackoffCalled: ((Int, Exception) -> Unit)? = null,
        private val onRunBlock: (suspend () -> Unit)? = null
    ) : BaseJob(
        Job.Parameters.Builder("test-job-id")
            .setMaxAttempts(3)
            .build()
    ) {
        override fun serialize(): Data = Data.EMPTY

        override fun getFactoryKey(): String = "TestJob"

        override fun onFailure() {
            
        }

        override suspend fun onRun() {
            onRunBlock?.invoke()
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
