package com.difft.android.chat.jobs

import android.app.Application
import com.difft.android.PushReactionSendJobFactory
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.JobManager
import com.difft.android.chat.jobmanager.impl.JsonDataSerializer
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.video.exo.SimpleExoPlayerPool
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.RealSource
import difft.android.messageserialization.model.TextMessage
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for [ReactionSendCoordinator]. Uses real Gson + JsonDataSerializer to round-trip
 * the actual serializedData shape; mocks JobManager + factory. Each test builds the
 * coordinator with `backgroundScope` so the test scheduler can drive the internal launches.
 * B7 uses UnconfinedTestDispatcher + CompletableDeferred barriers for deterministic
 * concurrency.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReactionSendCoordinatorTest {

    // Production Gson wiring — For.Account/For.Group must round-trip.
    private val gson: Gson = run {
        val valueAdapter = com.difft.android.chat.jobs.RuntimeTypeAdapterFactory.of(For::class.java)
            .registerSubtype(For.Account::class.java)
            .registerSubtype(For.Group::class.java)
        GsonBuilder().registerTypeAdapterFactory(valueAdapter).create()
    }
    private val dataSerializer = JsonDataSerializer()

    // jobManager is shared because ApplicationDependencies caches the first
    // getJobManager() result in a @Volatile field we can't reset between tests.
    // clearMocks in @After keeps each test independent.
    private val jobManager: JobManager = sharedJobManager
    private val factory = mockk<PushReactionSendJobFactory>(relaxed = true)

    companion object {
        private val sharedJobManager: JobManager = mockk(relaxed = true)
    }

    private val conversationId = "+10000000001"
    // MessageId.idValue = "<ts><source-without-plus><device>"
    private val realMessageId = "1699999999000" + "200000000002" + "1"
    private val emoji = "👍"
    private val uid = "self-uid"
    private val originTimestamp = 1_700_000_000_000L

    @Before
    fun setUp() {
        // mockkObject doesn't intercept @JvmStatic reliably; seed the real init() with
        // a stub Provider so getJobManager() returns our mock. init() is idempotent.
        if (!ApplicationDependencies.isInitialized()) {
            ApplicationDependencies.init(
                mockk<Application>(relaxed = true),
                object : ApplicationDependencies.Provider {
                    override fun provideJobManager(): JobManager = jobManager
                    override fun provideExoPlayerPool(): SimpleExoPlayerPool =
                        mockk(relaxed = true)
                },
            )
        }
        every { jobManager.cancel(any()) } just Runs
        every { jobManager.add(any()) } just Runs
        every { factory.create(any(), any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearMocks(jobManager, factory)
    }

    // Each test builds its own coordinator. The injected CoroutineScope shares the test's
    // CoroutineContext (so the test scheduler drives `scope.launch { ... }`) but uses a
    // SupervisorJob so failures in the launched coroutine don't fail the test scope.
    // advanceUntilIdle() drains the launched work deterministically.
    private fun TestScope.newCoordinator(
        serializer: Data.Serializer = dataSerializer,
    ) = ReactionSendCoordinator(
        gson,
        serializer,
        CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob()),
    )

    private fun buildReaction(
        emoji: String = this.emoji,
        uid: String = this.uid,
        originTs: Long = this.originTimestamp,
        realSourceSender: String = "+200000000002",
        realSourceTs: Long = 1_699_999_999_000L,
        realSourceDevice: Int = 1,
    ): Reaction = Reaction(
        emoji = emoji,
        uid = uid,
        remove = false,
        originTimestamp = originTs,
        realSource = RealSource(
            source = realSourceSender,
            sourceDevice = realSourceDevice,
            timestamp = realSourceTs,
            serverTimestamp = realSourceTs + 500L,
        ),
    )

    private fun buildTextMessage(
        reaction: Reaction,
        msgId: String = "envelope-${System.nanoTime()}",
        conversationId: String = this@ReactionSendCoordinatorTest.conversationId,
    ): TextMessage = TextMessage(
        id = msgId,
        fromWho = For.Account("self-uid"),
        forWhat = For.Account(conversationId),
        systemShowTimestamp = reaction.originTimestamp,
        timeStamp = reaction.originTimestamp,
        receivedTimeStamp = reaction.originTimestamp,
        sendType = 0,
        expiresInSeconds = 0,
        notifySequenceId = 0,
        sequenceId = 0,
        mode = 0,
        text = "",
        reactions = mutableListOf(reaction),
    )

    private fun buildJobSpec(
        id: String,
        textMessage: TextMessage,
        isRunning: Boolean = false,
        factoryKey: String = PushReactionSendJob.KEY,
        queueKey: String = "[${PushReactionSendJob.KEY}::$conversationId]",
        serializedData: String? = null,
    ): JobSpec {
        val payload = serializedData ?: dataSerializer.serialize(
            Data.Builder()
                .putString(PushReactionSendJob.KEY_MESSAGE_OUT, gson.toJson(textMessage))
                .build()
        )
        return JobSpec(
            id = id,
            factoryKey = factoryKey,
            queueKey = queueKey,
            createTime = 0L,
            nextRunAttemptTime = 0L,
            runAttempt = 0,
            maxAttempts = Job.Parameters.UNLIMITED,
            lifespan = 0L,
            serializedData = payload,
            isRunning = isRunning,
            isMemoryOnly = false,
        )
    }

    @Test
    fun `B1 - single match - matching pending job is cancelled and new job added`() = runTest {
        val coordinator = newCoordinator()
        val reaction = buildReaction()
        val tm = buildTextMessage(reaction)
        val existingSpec = buildJobSpec("spec-1", tm)
        coEvery { jobManager.findJobsInQueue(any()) } returns listOf(existingSpec)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = reaction,
            textMessage = tm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 1) { jobManager.cancel("spec-1") }
        verify(exactly = 1) { jobManager.add(any()) }
    }

    @Test
    fun `B2 - different emoji - no cancel, only add`() = runTest {
        val coordinator = newCoordinator()
        val newReaction = buildReaction(emoji = "❤")
        val newTm = buildTextMessage(newReaction)
        val oldReaction = buildReaction(emoji = "👍")
        val oldTm = buildTextMessage(oldReaction)
        val existingSpec = buildJobSpec("spec-other-emoji", oldTm)
        coEvery { jobManager.findJobsInQueue(any()) } returns listOf(existingSpec)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = newReaction,
            textMessage = newTm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 0) { jobManager.cancel(any()) }
        verify(exactly = 1) { jobManager.add(any()) }
    }

    @Test
    fun `B3 - different realMessageId - no cancel, only add`() = runTest {
        val coordinator = newCoordinator()
        val newReaction = buildReaction()
        val newTm = buildTextMessage(newReaction)
        // Same emoji + uid, but realSource points to a DIFFERENT target message.
        val otherTargetReaction = buildReaction(realSourceTs = 1_588_888_888_000L)
        val otherTargetTm = buildTextMessage(otherTargetReaction)
        val existingSpec = buildJobSpec("spec-other-target", otherTargetTm)
        coEvery { jobManager.findJobsInQueue(any()) } returns listOf(existingSpec)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = newReaction,
            textMessage = newTm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 0) { jobManager.cancel(any()) }
        verify(exactly = 1) { jobManager.add(any()) }
    }

    @Test
    fun `B4 - different uid - no cancel, only add`() = runTest {
        val coordinator = newCoordinator()
        val newReaction = buildReaction(uid = "self-uid")
        val newTm = buildTextMessage(newReaction)
        val otherUidReaction = buildReaction(uid = "OTHER-uid")
        val otherUidTm = buildTextMessage(otherUidReaction)
        val existingSpec = buildJobSpec("spec-other-uid", otherUidTm)
        coEvery { jobManager.findJobsInQueue(any()) } returns listOf(existingSpec)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = newReaction,
            textMessage = newTm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 0) { jobManager.cancel(any()) }
        verify(exactly = 1) { jobManager.add(any()) }
    }

    // Defensive: in normal operation only one pending job exists per (msgId, emoji, uid)
    // because of FIFO+Mutex. If multiple ever do, the coordinator must cancel ALL of them.
    @Test
    fun `B5 - three matching pending jobs - all three are cancelled, one new added`() = runTest {
        val coordinator = newCoordinator()
        val reaction = buildReaction()
        val tm = buildTextMessage(reaction)
        val spec1 = buildJobSpec("spec-1", tm)
        val spec2 = buildJobSpec("spec-2", tm)
        val spec3 = buildJobSpec("spec-3", tm)
        coEvery { jobManager.findJobsInQueue(any()) } returns listOf(spec1, spec2, spec3)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = reaction,
            textMessage = tm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 1) { jobManager.cancel("spec-1") }
        verify(exactly = 1) { jobManager.cancel("spec-2") }
        verify(exactly = 1) { jobManager.cancel("spec-3") }
        verify(exactly = 1) { jobManager.add(any()) }
    }

    @Test
    fun `B6 - empty queue - no cancel, only add`() = runTest {
        val coordinator = newCoordinator()
        coEvery { jobManager.findJobsInQueue(any()) } returns emptyList()
        val reaction = buildReaction()
        val tm = buildTextMessage(reaction)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = reaction,
            textMessage = tm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 0) { jobManager.cancel(any()) }
        verify(exactly = 1) { jobManager.add(any()) }
    }

    /**
     * B7 — concurrent same-conversation enqueues. Per-conversation Mutex MUST serialize
     * the find→cancel→add window so the second call sees the first's added job and
     * supersedes it. Uses CompletableDeferred barriers for deterministic interleaving.
     *
     * Tautology guard (one-time manual verification): temporarily replace
     * `mutex.withLock { ... }` with `run { ... }` in ReactionSendCoordinator
     * (impl, NOT this test). Re-run — must FAIL with cancel("spec-1") count = 0
     * (B races ahead, observes empty queue). Restore Mutex; must pass.
     */
    @Test
    fun `B7 - concurrent enqueue in same conversation - mutex serializes RMW`() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator = newCoordinator()
            val firstCallEntered = CompletableDeferred<Unit>()
            val firstCallProceed = CompletableDeferred<Unit>()
            var callCount = 0
            val addedSpecIds = mutableListOf<String>()

            val reactionA = buildReaction(originTs = 1_700_000_000_000L)
            val tmA = buildTextMessage(reactionA, msgId = "tm-A")
            val reactionB = buildReaction(originTs = 1_700_000_000_500L)
            val tmB = buildTextMessage(reactionB, msgId = "tm-B")
            val spec1ForB = buildJobSpec("spec-1", tmA)

            val jobA = mockk<PushReactionSendJob>(relaxed = true)
            val jobB = mockk<PushReactionSendJob>(relaxed = true)
            every { factory.create(null, tmA) } returns jobA
            every { factory.create(null, tmB) } returns jobB
            every { jobManager.add(jobA) } answers { addedSpecIds.add("spec-1"); Unit }
            every { jobManager.add(jobB) } answers { addedSpecIds.add("spec-2"); Unit }

            coEvery { jobManager.findJobsInQueue(any()) } coAnswers {
                val n = ++callCount
                if (n == 1) {
                    firstCallEntered.complete(Unit)
                    firstCallProceed.await()
                    emptyList<JobSpec>()
                } else {
                    if (addedSpecIds.contains("spec-1")) listOf(spec1ForB) else emptyList<JobSpec>()
                }
            }

            // A launches into backgroundScope; under UnconfinedTestDispatcher it runs
            // eagerly until firstCallProceed.await() suspension inside the stub.
            coordinator.enqueueReactionWithDedupe(
                conversationId = conversationId,
                realMessageId = realMessageId,
                reaction = reactionA,
                textMessage = tmA,
                factory = factory,
            )
            firstCallEntered.await()

            // B launches; tries to acquire the same per-conversation Mutex → suspends
            // because A still holds it. Without the Mutex, B would race ahead and
            // observe an empty queue (callCount=2 before A's add).
            coordinator.enqueueReactionWithDedupe(
                conversationId = conversationId,
                realMessageId = realMessageId,
                reaction = reactionB,
                textMessage = tmB,
                factory = factory,
            )
            yield()

            // Release A — it completes its withLock body (finds empty, adds spec-1),
            // releases the mutex. B then acquires it and sees A's added spec-1.
            firstCallProceed.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 1) { jobManager.cancel("spec-1") }
            verify(exactly = 2) { jobManager.add(any()) }
        }

    /**
     * B8 — concurrent ops on DIFFERENT conversations get DIFFERENT mutexes and run in
     * parallel without contention. Both calls' stubs must complete; if a single shared
     * mutex were used, this would deadlock.
     */
    @Test
    fun `B8 - concurrent enqueue in different conversations - mutexes independent`() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator = newCoordinator()
            val convA = "+11111111111"
            val convB = "+22222222222"
            val enteredA = CompletableDeferred<Unit>()
            val enteredB = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()

            val reactionA = buildReaction()
            val tmA = buildTextMessage(reactionA, conversationId = convA)
            val reactionB = buildReaction()
            val tmB = buildTextMessage(reactionB, conversationId = convB)

            coEvery { jobManager.findJobsInQueue("[${PushReactionSendJob.KEY}::$convA]") } coAnswers {
                enteredA.complete(Unit); proceed.await(); emptyList<JobSpec>()
            }
            coEvery { jobManager.findJobsInQueue("[${PushReactionSendJob.KEY}::$convB]") } coAnswers {
                enteredB.complete(Unit); proceed.await(); emptyList<JobSpec>()
            }

            coordinator.enqueueReactionWithDedupe(
                conversationId = convA,
                realMessageId = realMessageId,
                reaction = reactionA,
                textMessage = tmA,
                factory = factory,
            )
            coordinator.enqueueReactionWithDedupe(
                conversationId = convB,
                realMessageId = realMessageId,
                reaction = reactionB,
                textMessage = tmB,
                factory = factory,
            )

            // Both must enter concurrently (deadlock here = shared mutex bug).
            enteredA.await()
            enteredB.await()
            proceed.complete(Unit)
            advanceUntilIdle()

            verify(exactly = 0) { jobManager.cancel(any()) }
            verify(exactly = 2) { jobManager.add(any()) }
        }

    // Inner JSON malformed → JsonSyntaxException (RuntimeException ⊂ Exception). Matcher
    // catches it as Throwable, returns false; add still runs.
    @Test
    fun `B9 - inner JSON parse failure - matcher tolerates exception, no cancel, still add`() =
        runTest {
            val coordinator = newCoordinator()
            val reaction = buildReaction()
            val tm = buildTextMessage(reaction)
            val outerData = Data.Builder()
                .putString(PushReactionSendJob.KEY_MESSAGE_OUT, "{not valid TextMessage JSON")
                .build()
            val corruptSpec = JobSpec(
                id = "spec-corrupt-inner",
                factoryKey = PushReactionSendJob.KEY,
                queueKey = "[${PushReactionSendJob.KEY}::$conversationId]",
                createTime = 0L,
                nextRunAttemptTime = 0L,
                runAttempt = 0,
                maxAttempts = Job.Parameters.UNLIMITED,
                lifespan = 0L,
                serializedData = dataSerializer.serialize(outerData),
                isRunning = false,
                isMemoryOnly = false,
            )
            coEvery { jobManager.findJobsInQueue(any()) } returns listOf(corruptSpec)

            coordinator.enqueueReactionWithDedupe(
                conversationId = conversationId,
                realMessageId = realMessageId,
                reaction = reaction,
                textMessage = tm,
                factory = factory,
            )
            advanceUntilIdle()

            verify(exactly = 0) { jobManager.cancel(any()) }
            verify(exactly = 1) { jobManager.add(any()) }
        }

    // Outer Data JSON corrupt → JsonDataSerializer throws AssertionError (extends Error,
    // not Exception). Pins HIGH-1 fix: matcher's `catch (Throwable)` swallows it.
    @Test
    fun `B9b - outer Data unparseable AssertionError - matcher tolerates Error, no cancel, still add`() =
        runTest {
            val coordinator = newCoordinator()
            val reaction = buildReaction()
            val tm = buildTextMessage(reaction)
            val corruptSpec = JobSpec(
                id = "spec-corrupt-outer",
                factoryKey = PushReactionSendJob.KEY,
                queueKey = "[${PushReactionSendJob.KEY}::$conversationId]",
                createTime = 0L,
                nextRunAttemptTime = 0L,
                runAttempt = 0,
                maxAttempts = Job.Parameters.UNLIMITED,
                lifespan = 0L,
                serializedData = "this is not valid Data JSON at all { ::: }",
                isRunning = false,
                isMemoryOnly = false,
            )
            coEvery { jobManager.findJobsInQueue(any()) } returns listOf(corruptSpec)

            coordinator.enqueueReactionWithDedupe(
                conversationId = conversationId,
                realMessageId = realMessageId,
                reaction = reaction,
                textMessage = tm,
                factory = factory,
            )
            advanceUntilIdle()

            verify(exactly = 0) { jobManager.cancel(any()) }
            verify(exactly = 1) { jobManager.add(any()) }
        }

    @Test
    fun `B10 - reactions null on existing spec - no cancel, still add`() = runTest {
        val coordinator = newCoordinator()
        val newReaction = buildReaction()
        val newTm = buildTextMessage(newReaction)
        val noReactionsTm = TextMessage(
            id = "no-reactions",
            fromWho = For.Account("self-uid"),
            forWhat = For.Account(conversationId),
            systemShowTimestamp = 0L,
            timeStamp = 0L,
            receivedTimeStamp = 0L,
            sendType = 0,
            expiresInSeconds = 0,
            notifySequenceId = 0,
            sequenceId = 0,
            mode = 0,
            text = "",
            reactions = null,
        )
        val noReactionsSpec = buildJobSpec("spec-no-reactions", noReactionsTm)
        coEvery { jobManager.findJobsInQueue(any()) } returns listOf(noReactionsSpec)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = newReaction,
            textMessage = newTm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 0) { jobManager.cancel(any()) }
        verify(exactly = 1) { jobManager.add(any()) }
    }

    // Belt-and-suspenders — queueKey already filters by factory at the framework level.
    @Test
    fun `B11 - wrong factoryKey - defensive filter rejects, no cancel`() = runTest {
        val coordinator = newCoordinator()
        val reaction = buildReaction()
        val tm = buildTextMessage(reaction)
        val wrongFactorySpec = buildJobSpec(
            id = "spec-other-factory",
            textMessage = tm,
            factoryKey = "SomeOtherJob",
        )
        coEvery { jobManager.findJobsInQueue(any()) } returns listOf(wrongFactorySpec)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = reaction,
            textMessage = tm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 0) { jobManager.cancel(any()) }
        verify(exactly = 1) { jobManager.add(any()) }
    }

    // Sibling of B6 — pins the `factory.create(null, tm)` invocation contract.
    @Test
    fun `B12 - empty queue - new job built with null parameters and the supplied textMessage`() =
        runTest {
            val coordinator = newCoordinator()
            coEvery { jobManager.findJobsInQueue(any()) } returns emptyList()
            val reaction = buildReaction()
            val tm = buildTextMessage(reaction)

            coordinator.enqueueReactionWithDedupe(
                conversationId = conversationId,
                realMessageId = realMessageId,
                reaction = reaction,
                textMessage = tm,
                factory = factory,
            )
            advanceUntilIdle()

            verify(exactly = 1) { factory.create(null, tm) }
            verify(exactly = 1) { jobManager.add(any()) }
            verify(exactly = 0) { jobManager.cancel(any()) }
        }

    @Test
    fun `B13 - mutex map grows to exactly N entries for N distinct conversations`() = runTest {
        val coordinator = newCoordinator(JsonDataSerializer())
        coEvery { jobManager.findJobsInQueue(any()) } returns emptyList()

        val reaction = buildReaction()
        repeat(50) { i ->
            val convId = "+conv-$i"
            val tm = buildTextMessage(reaction, conversationId = convId)
            coordinator.enqueueReactionWithDedupe(
                conversationId = convId,
                realMessageId = realMessageId,
                reaction = reaction,
                textMessage = tm,
                factory = factory,
            )
        }
        advanceUntilIdle()

        assertEquals(50, coordinator.mutexes.size)
    }

    // Coordinator does NOT pre-filter on isRunning — cancellation of a running job is
    // best-effort (Risk 3 in design). Receiver-side LWW bottoms out if HTTP already sent.
    @Test
    fun `B14 - matching running job - still cancelled (best-effort, see Risk 3)`() = runTest {
        val coordinator = newCoordinator()
        val reaction = buildReaction()
        val tm = buildTextMessage(reaction)
        val runningSpec = buildJobSpec("spec-running", tm, isRunning = true)
        coEvery { jobManager.findJobsInQueue(any()) } returns listOf(runningSpec)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = reaction,
            textMessage = tm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 1) { jobManager.cancel("spec-running") }
        verify(exactly = 1) { jobManager.add(any()) }
    }

    // L.i { ... } is a lazy lambda fired immediately before cancel(spec.id) — verifying
    // cancel-after-match is a sufficient proxy without mocking the L object.
    @Test
    fun `B15 - logging path is exercised on supersede (cancel-after-match proxy)`() = runTest {
        val coordinator = newCoordinator()
        val reaction = buildReaction()
        val tm = buildTextMessage(reaction)
        val existingSpec = buildJobSpec("spec-to-log", tm)
        coEvery { jobManager.findJobsInQueue(any()) } returns listOf(existingSpec)

        coordinator.enqueueReactionWithDedupe(
            conversationId = conversationId,
            realMessageId = realMessageId,
            reaction = reaction,
            textMessage = tm,
            factory = factory,
        )
        advanceUntilIdle()

        verify(exactly = 1) { jobManager.cancel("spec-to-log") }
        assertNotNull(existingSpec.serializedData, "fixture broken: spec.serializedData null")
    }
}
