package com.difft.android.chat.jobmanager

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InAppSchedulerTest {

    private val jobManager: JobManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val scheduler = InAppScheduler(jobManager, scope = testScope)

    @After
    fun tearDown() {
        clearMocks(jobManager)
    }

    @Test
    fun `schedule fires wakeUp after delay`() = testScope.runTest {
        val constraint = mockConstraint(isMet = true)

        scheduler.schedule(1000L, listOf(constraint))

        // Before delay elapses, wakeUp should not have been called
        verify(exactly = 0) { jobManager.wakeUp() }

        advanceTimeBy(1001)

        verify(exactly = 1) { jobManager.wakeUp() }
    }

    @Test
    fun `schedule does not fire if constraints not met`() = testScope.runTest {
        val constraint = mockConstraint(isMet = false)

        scheduler.schedule(1000L, listOf(constraint))

        advanceTimeBy(2000)

        verify(exactly = 0) { jobManager.wakeUp() }
    }

    @Test
    fun `schedule does not fire if delay is zero`() = testScope.runTest {
        val constraint = mockConstraint(isMet = true)

        scheduler.schedule(0L, listOf(constraint))

        advanceTimeBy(1000)

        verify(exactly = 0) { jobManager.wakeUp() }
    }

    @Test
    fun `schedule does not fire if delay is negative`() = testScope.runTest {
        val constraint = mockConstraint(isMet = true)

        scheduler.schedule(-1L, listOf(constraint))

        advanceTimeBy(1000)

        verify(exactly = 0) { jobManager.wakeUp() }
    }

    @Test
    fun `schedule with multiple constraints all met fires wakeUp`() = testScope.runTest {
        val constraint1 = mockConstraint(isMet = true)
        val constraint2 = mockConstraint(isMet = true)

        scheduler.schedule(500L, listOf(constraint1, constraint2))

        advanceTimeBy(501)

        verify(exactly = 1) { jobManager.wakeUp() }
    }

    @Test
    fun `schedule with one constraint not met does not fire`() = testScope.runTest {
        val constraint1 = mockConstraint(isMet = true)
        val constraint2 = mockConstraint(isMet = false)

        scheduler.schedule(500L, listOf(constraint1, constraint2))

        advanceTimeBy(1000)

        verify(exactly = 0) { jobManager.wakeUp() }
    }

    @Test
    fun `multiple concurrent schedules all fire independently`() = testScope.runTest {
        val constraint = mockConstraint(isMet = true)

        scheduler.schedule(1000L, listOf(constraint))
        scheduler.schedule(2000L, listOf(constraint))

        advanceTimeBy(1001)
        verify(exactly = 1) { jobManager.wakeUp() }

        advanceTimeBy(1000)
        verify(exactly = 2) { jobManager.wakeUp() }
    }

    @Test
    fun `schedule with empty constraints fires wakeUp`() = testScope.runTest {
        // Empty list -- all() on empty list returns true
        scheduler.schedule(500L, emptyList())

        advanceTimeBy(501)

        verify(exactly = 1) { jobManager.wakeUp() }
    }

    private fun mockConstraint(isMet: Boolean): Constraint {
        return mockk<Constraint> {
            every { isMet() } returns isMet
        }
    }
}
