package com.difft.android.chat.jobmanager

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobResultTest {

    // region success

    @Test
    fun `success returns isSuccess true`() {
        val result = Job.Result.success()
        assertTrue(result.isSuccess())
    }

    @Test
    fun `success returns isRetry false`() {
        val result = Job.Result.success()
        assertFalse(result.isRetry())
    }

    @Test
    fun `success returns isFailure false`() {
        val result = Job.Result.success()
        assertFalse(result.isFailure())
    }

    @Test
    fun `success has no exception`() {
        val result = Job.Result.success()
        assertNull(result.getException())
    }

    @Test
    fun `success toString contains SUCCESS`() {
        val result = Job.Result.success()
        assertEquals("SUCCESS", result.toString())
    }

    // endregion

    // region retry

    @Test
    fun `retry returns isRetry true`() {
        val result = Job.Result.retry(5000L)
        assertTrue(result.isRetry())
    }

    @Test
    fun `retry returns isSuccess false`() {
        val result = Job.Result.retry(5000L)
        assertFalse(result.isSuccess())
    }

    @Test
    fun `retry returns isFailure false`() {
        val result = Job.Result.retry(5000L)
        assertFalse(result.isFailure())
    }

    @Test
    fun `retry backoffInterval matches`() {
        val result = Job.Result.retry(12345L)
        assertEquals(12345L, result.getBackoffInterval())
    }

    @Test
    fun `retry with zero backoff`() {
        val result = Job.Result.retry(0L)
        assertEquals(0L, result.getBackoffInterval())
        assertTrue(result.isRetry())
    }

    @Test
    fun `retry has no exception`() {
        val result = Job.Result.retry(5000L)
        assertNull(result.getException())
    }

    @Test
    fun `retry toString contains RETRY`() {
        val result = Job.Result.retry(5000L)
        assertEquals("RETRY", result.toString())
    }

    // endregion

    // region failure

    @Test
    fun `failure returns isFailure true`() {
        val result = Job.Result.failure()
        assertTrue(result.isFailure())
    }

    @Test
    fun `failure returns isSuccess false`() {
        val result = Job.Result.failure()
        assertFalse(result.isSuccess())
    }

    @Test
    fun `failure returns isRetry false`() {
        val result = Job.Result.failure()
        assertFalse(result.isRetry())
    }

    @Test
    fun `failure has no exception`() {
        val result = Job.Result.failure()
        assertNull(result.getException())
    }

    @Test
    fun `failure toString contains FAILURE`() {
        val result = Job.Result.failure()
        assertEquals("FAILURE", result.toString())
    }

    // endregion

    // region fatalFailure

    @Test
    fun `fatalFailure returns isFailure true`() {
        val exception = RuntimeException("fatal error")
        val result = Job.Result.fatalFailure(exception)
        assertTrue(result.isFailure())
    }

    @Test
    fun `fatalFailure returns isSuccess false`() {
        val exception = RuntimeException("fatal error")
        val result = Job.Result.fatalFailure(exception)
        assertFalse(result.isSuccess())
    }

    @Test
    fun `fatalFailure returns isRetry false`() {
        val exception = RuntimeException("fatal error")
        val result = Job.Result.fatalFailure(exception)
        assertFalse(result.isRetry())
    }

    @Test
    fun `fatalFailure has the provided exception`() {
        val exception = RuntimeException("fatal error")
        val result = Job.Result.fatalFailure(exception)

        val retrieved = result.getException()
        assertNotNull(retrieved)
        assertEquals("fatal error", retrieved.message)
        assertTrue(retrieved === exception, "Should be the same exception instance")
    }

    @Test
    fun `fatalFailure toString contains FATAL_FAILURE`() {
        val exception = RuntimeException("fatal error")
        val result = Job.Result.fatalFailure(exception)
        assertEquals("FATAL_FAILURE", result.toString())
    }

    // endregion

    // region success is singleton

    @Test
    fun `success returns same instance`() {
        val result1 = Job.Result.success()
        val result2 = Job.Result.success()
        assertTrue(result1 === result2, "success() should return the same singleton instance")
    }

    // endregion

    // region failure is singleton

    @Test
    fun `failure returns same instance`() {
        val result1 = Job.Result.failure()
        val result2 = Job.Result.failure()
        assertTrue(result1 === result2, "failure() should return the same singleton instance")
    }

    // endregion

    // region retry creates new instances

    @Test
    fun `retry creates distinct instances`() {
        val result1 = Job.Result.retry(1000L)
        val result2 = Job.Result.retry(1000L)
        assertFalse(result1 === result2, "retry() should create new instances")
    }

    @Test
    fun `retry with different backoffs have different intervals`() {
        val result1 = Job.Result.retry(1000L)
        val result2 = Job.Result.retry(2000L)

        assertEquals(1000L, result1.getBackoffInterval())
        assertEquals(2000L, result2.getBackoffInterval())
    }

    // endregion

    // region fatalFailure creates new instances

    @Test
    fun `fatalFailure creates distinct instances`() {
        val ex1 = RuntimeException("error 1")
        val ex2 = RuntimeException("error 2")
        val result1 = Job.Result.fatalFailure(ex1)
        val result2 = Job.Result.fatalFailure(ex2)

        assertFalse(result1 === result2, "fatalFailure() should create new instances")
        assertEquals("error 1", result1.getException()!!.message)
        assertEquals("error 2", result2.getException()!!.message)
    }

    // endregion
}
