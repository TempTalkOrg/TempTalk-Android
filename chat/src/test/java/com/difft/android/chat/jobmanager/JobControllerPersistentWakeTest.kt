package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.util.Debouncer
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals





class JobControllerPersistentWakeTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobStorage = mockk<JobStorage>(relaxed = true)
    private val jobInstantiator = mockk<JobInstantiator>(relaxed = true)
    private val constraintInstantiator = mockk<ConstraintInstantiator>(relaxed = true)
    private val dataSerializer = mockk<Data.Serializer>(relaxed = true)
    private val jobTracker = mockk<JobTracker>(relaxed = true)
    private val scheduler = mockk<Scheduler>(relaxed = true)
    private val persistentScheduler = mockk<Scheduler>(relaxed = true)
    private val debouncer = mockk<Debouncer>(relaxed = true)
    private val callback = mockk<JobController.Callback>(relaxed = true)

    private lateinit var controller: JobController

    @Before
    fun setUp() {
        every { dataSerializer.serialize(any()) } returns "{}"
        every { dataSerializer.deserialize(any()) } returns Data.EMPTY
        controller = JobController(
            application, jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, persistentScheduler, debouncer, callback
        )
    }

    @After
    fun tearDown() {
        clearMocks(
            jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, persistentScheduler, debouncer, callback
        )
    }

    

    @Test
    fun `pendingWakeUpIds is empty once storage is drained`() {
        every { jobStorage.getAllJobSpecs() } returns emptyList()

        assertEquals(emptySet(), controller.pendingWakeUpIds())
    }

    @Test
    fun `pendingWakeUpIds counts a constraint-less spec that is already running`() {
        
        
        every { jobStorage.getAllJobSpecs() } returns
            listOf(buildJobSpec("wake-running", isRunning = true))
        every { jobStorage.getConstraintSpecs("wake-running") } returns emptyList()

        assertEquals(setOf(plainJobId), controller.pendingWakeUpIds())
        verify(exactly = 0) { jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any()) }
    }

    @Test
    fun `pendingWakeUpIds counts a network spec waiting on a future attempt time`() {
        val spec = buildJobSpec("wake-waiting", nextRunAttemptTime = System.currentTimeMillis() + 600_000L)
        every { jobStorage.getAllJobSpecs() } returns listOf(spec)
        stubNetworkConstraint("wake-waiting")

        
        
        assertEquals(setOf(networkJobId), controller.pendingWakeUpIds())
    }

    @Test
    fun `pendingWakeUpIds answers each group independently when storage holds both`() {
        every { jobStorage.getAllJobSpecs() } returns
            listOf(buildJobSpec("wake-plain"), buildJobSpec("wake-network"))
        every { jobStorage.getConstraintSpecs("wake-plain") } returns emptyList()
        stubNetworkConstraint("wake-network")

        assertEquals(setOf(plainJobId, networkJobId), controller.pendingWakeUpIds())
    }

    @Test
    fun `pendingWakeUpIds skips a spec whose constraint key has no factory`() {
        every { jobStorage.getAllJobSpecs() } returns listOf(buildJobSpec("wake-unknown"))
        every { jobStorage.getConstraintSpecs("wake-unknown") } returns
            listOf(ConstraintSpec("wake-unknown", UNKNOWN_KEY, false))
        every { constraintInstantiator.instantiate(UNKNOWN_KEY) } throws
            IllegalStateException("no matching factory was found")

        assertEquals(emptySet(), controller.pendingWakeUpIds())
    }

    @Test
    fun `pendingWakeUpIds ignores a memory-only spec in every group`() {
        
        
        every { jobStorage.getAllJobSpecs() } returns
            listOf(buildJobSpec("wake-volatile", isMemoryOnly = true))
        every { jobStorage.getConstraintSpecs("wake-volatile") } returns emptyList()

        assertEquals(emptySet(), controller.pendingWakeUpIds())
    }

    

    @Test
    fun `cancelIdleWakeUps keeps nothing once storage is drained`() {
        every { jobStorage.getAllJobSpecs() } returns emptyList()

        controller.cancelIdleWakeUps()

        verify(exactly = 1) { persistentScheduler.cancelAllExcept(emptySet()) }
    }

    @Test
    fun `cancelIdleWakeUps keeps only the group that still owns work`() {
        
        
        every { jobStorage.getAllJobSpecs() } returns
            listOf(buildJobSpec("release-waiting", nextRunAttemptTime = System.currentTimeMillis()))
        stubNetworkConstraint("release-waiting")

        controller.cancelIdleWakeUps()

        verify(exactly = 1) { persistentScheduler.cancelAllExcept(setOf(networkJobId)) }
    }

    @Test
    fun `cancelIdleWakeUps keeps both groups while each still owns work`() {
        every { jobStorage.getAllJobSpecs() } returns
            listOf(buildJobSpec("release-plain"), buildJobSpec("release-network"))
        every { jobStorage.getConstraintSpecs("release-plain") } returns emptyList()
        stubNetworkConstraint("release-network")

        controller.cancelIdleWakeUps()

        verify(exactly = 1) { persistentScheduler.cancelAllExcept(setOf(plainJobId, networkJobId)) }
    }

    @Test
    fun `cancelIdleWakeUps keeps nothing when only memory-only specs remain`() {
        
        
        every { jobStorage.getAllJobSpecs() } returns
            listOf(buildJobSpec("release-volatile", isMemoryOnly = true))
        every { jobStorage.getConstraintSpecs("release-volatile") } returns emptyList()

        controller.cancelIdleWakeUps()

        verify(exactly = 1) { persistentScheduler.cancelAllExcept(emptySet()) }
    }

    

    private fun stubNetworkConstraint(specId: String) {
        every { jobStorage.getConstraintSpecs(specId) } returns
            listOf(ConstraintSpec(specId, NETWORK_KEY, false))
        every { constraintInstantiator.instantiate(NETWORK_KEY) } returns TestNetworkConstraint()
    }

    private fun buildJobSpec(
        id: String,
        nextRunAttemptTime: Long = 0,
        isRunning: Boolean = false,
        isMemoryOnly: Boolean = false
    ): JobSpec = JobSpec(
        id = id,
        factoryKey = "TestFactory",
        queueKey = null,
        createTime = System.currentTimeMillis(),
        nextRunAttemptTime = nextRunAttemptTime,
        runAttempt = 0,
        maxAttempts = 3,
        lifespan = Job.Parameters.IMMORTAL,
        serializedData = "{}",
        isRunning = isRunning,
        isMemoryOnly = isMemoryOnly
    )

    private companion object {
        const val NETWORK_KEY = "NetworkConstraint"
        const val UNKNOWN_KEY = "GoneConstraint"

        
        val plainJobId = persistentJobId(emptyList())
        val networkJobId = persistentJobId(listOf(TestNetworkConstraint()))
    }
}
