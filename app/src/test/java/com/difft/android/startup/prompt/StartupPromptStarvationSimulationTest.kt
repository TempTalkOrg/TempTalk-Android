package com.difft.android.startup.prompt

import com.difft.android.base.notification.ReadinessIssue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.gson.Gson
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Starvation guard (#1355): 90 simulated days of sessions against the real coordinator, store and
 * back-off policy, with a seeded mix of user behaviour (decline, outside tap, going to the background
 * with the prompt open, Settings without fixing, recreation, process death), app lock at session
 * start, transient gates, late UNKNOWN providers and dual-pane conversations. The target prompt's
 * problem never goes away; lower prompts may be present too. A prompt left open stays current and is
 * seen again on return, which counts as shown in that session.
 *
 * Asserted: the target is shown at least once in every 31-day window, and a session never shows more
 * than one cancelable prompt (a preempted one does not count). Clocks are the test scheduler's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupPromptStarvationSimulationTest {

    @Test
    fun `legacy migration guide never starves`() = simulate(PromptId.LEGACY_MIGRATION)

    @Test
    fun `notifications prompt never starves`() = simulate(PromptId.NOTIFICATIONS)

    @Test
    fun `background connection prompt never starves`() = simulate(PromptId.BACKGROUND_CONNECTION)

    @Test
    fun `full-screen prompt never starves`() = simulate(PromptId.FULL_SCREEN)

    @Test
    fun `optional update prompt never starves`() = simulate(PromptId.OPTIONAL_UPDATE)

    private fun simulate(target: PromptId) {
        SEEDS.forEach { seed -> runTest(timeout = 120.seconds) { Simulation(this, target, seed).run() } }
    }

    private enum class Behaviour { DECLINE, OUTSIDE, IGNORE, SETTINGS, RECREATE, KILL, KEEP_OPEN }

    private enum class After { CONTINUE, END_SESSION, KILL }

    private class Simulation(private val test: TestScope, private val target: PromptId, private val seed: Long) {
        private val random = Random(seed)
        private val store = StartupPromptStore(MemoryDataStore(), Gson())
        private val sessions = MutableStateFlow(PromptSession(0, active = false))
        private val gates = MutableStateFlow<Set<GateId>>(emptySet())
        private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

        /** Lower prompts whose problem is present as well, fixed per seed. */
        private val noise = PromptId.entries.filter { it > target && random.nextBoolean() }.toSet()

        private val showDays = sortedSetOf<Long>()
        private var systemDenials = random.nextInt(3)
        private var fcmResolvesAt = 0L
        private var updateResolvesAt = 0L
        private var process: CoroutineScope? = null
        private lateinit var coordinator: StartupPromptCoordinator

        // Host state of the current session.
        private var presentable = false
        private var dualPane = false
        private var mountedSeq: Long? = null
        private var mountedId: PromptId? = null
        private val shownThisSession = mutableMapOf<PromptId, Int>()

        private val providers: StartupPromptProviders = mockk {
            every { gates() } returns gates
            every { changes() } returns changes
            every { onSessionStart() } just runs
            coEvery { evaluate(any()) } answers { answer(firstArg()) }
        }

        private val now: Long get() = test.testScheduler.currentTime

        private fun present(id: PromptId) = id == target || id in noise

        private fun answer(id: PromptId): ProviderAnswer = when (id) {
            PromptId.BACKGROUND_CONNECTION -> if (now < fcmResolvesAt) ProviderAnswer.UNKNOWN else eligibleOrNot(id)
            PromptId.OPTIONAL_UPDATE -> if (now < updateResolvesAt) ProviderAnswer.UNKNOWN else eligibleOrNot(id, offerKey = "H1")
            PromptId.NOTIFICATIONS -> eligibleOrNot(id, ReadinessIssue.APP_NOTIFICATIONS_OFF, requestable = true)
            PromptId.FULL_SCREEN -> eligibleOrNot(id, ReadinessIssue.FULL_SCREEN_DENIED)
            PromptId.LEGACY_MIGRATION -> eligibleOrNot(id)
        }

        private fun eligibleOrNot(id: PromptId, issue: ReadinessIssue? = null, offerKey: String? = null, requestable: Boolean = false) =
            if (present(id)) ProviderAnswer(Eligibility.ELIGIBLE, issue, offerKey, requestable) else ProviderAnswer.NOT_ELIGIBLE

        suspend fun run() {
            startProcess()
            for (day in 0 until DAYS) {
                // The provider mock records every call; 90 days of them only cost heap.
                clearMocks(providers, answers = false, recordedCalls = true, childMocks = false, exclusionRules = false)
                val count = 1 + random.nextInt(MAX_SESSIONS_PER_DAY)
                val guaranteed = random.nextInt(count)
                val starts = List(count) { random.nextLong(0, DAY_MS - 200_000) }.sorted()
                starts.forEachIndexed { index, start ->
                    val at = day * DAY_MS + start
                    if (at > now) advance(at - now)
                    if (process == null || random.nextInt(10) == 0) startProcess()
                    session(day, guaranteed = index == guaranteed)
                }
            }
            assertWindows()
        }

        private fun startProcess() {
            process?.cancel()
            val scope = CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))
            process = scope
            fcmResolvesAt = now + random.nextLong(0, 15_000)
            updateResolvesAt = now + 2_000 + random.nextLong(0, 4_000)
            coordinator = StartupPromptCoordinator(store, providers, sessions, { BASE + now }, { now }, scope)
            coordinator.start()
            test.runCurrent()
        }

        private fun killProcess() {
            process?.cancel()
            process = null
        }

        private fun advance(ms: Long) {
            val before = now
            test.advanceTimeBy(ms)
            test.runCurrent()
            if (before < fcmResolvesAt && now >= fcmResolvesAt || before < updateResolvesAt && now >= updateResolvesAt) {
                changes.tryEmit(Unit)
                test.runCurrent()
            }
        }

        private fun report() {
            coordinator.setHostState(presentable, dualPane, systemDenials == 1)
            test.runCurrent()
        }

        private suspend fun session(day: Long, guaranteed: Boolean) {
            sessions.value = PromptSession(sessions.value.id + 1, active = true)
            test.runCurrent()
            presentable = false
            mountedSeq = null
            mountedId = null
            shownThisSession.clear()
            dualPane = random.nextInt(4) == 0
            val lockDelay = random.nextLong(0, 4_000)
            val presentableEver = guaranteed || random.nextInt(5) != 0
            val duration = if (guaranteed) 90_000L else random.nextLong(2_000, 120_000)
            val gateAt = if (random.nextInt(4) == 0) random.nextLong(0, duration) else -1L
            val gateUntil = gateAt + random.nextLong(1_000, 10_000)
            val killAt = if (!guaranteed && random.nextInt(20) == 0) random.nextLong(0, duration) else -1L
            var t = 0L
            var after = After.CONTINUE
            while (t < duration && after == After.CONTINUE) {
                if (presentableEver && !presentable && t >= lockDelay + SETTLE_MS) {
                    presentable = true
                    report()
                }
                if (dualPane && (guaranteed && t >= 30_000 || random.nextInt(40) == 0)) {
                    dualPane = false
                    if (presentable) report()
                }
                if (gateAt >= 0 && t == gateAt - gateAt % TICK_MS) {
                    gates.value = setOf(TRANSIENT_GATES.random(random))
                    test.runCurrent()
                }
                if (gateAt >= 0 && t >= gateUntil && gates.value.isNotEmpty()) {
                    gates.value = emptySet()
                    test.runCurrent()
                }
                if (killAt >= 0 && t >= killAt) {
                    after = After.KILL
                    break
                }
                after = render(day)
                if (after != After.CONTINUE) break
                advance(TICK_MS)
                t += TICK_MS
            }
            if (gates.value.isNotEmpty()) gates.value = emptySet()
            if (after == After.KILL) {
                killProcess()
                sessions.value = sessions.value.copy(active = false)
                return
            }
            presentable = false
            report()
            sessions.value = sessions.value.copy(active = false)
            test.runCurrent()
        }

        /** The host: unmounts what the coordinator removed, mounts the current ticket, plays a behaviour. */
        private suspend fun render(day: Long): After {
            val ticket = coordinator.current.value
            if (mountedSeq != null && ticket?.seq != mountedSeq) {
                // Removed by the coordinator: with a gate active it was preempted and does not count.
                if (gates.value.isNotEmpty()) mountedId?.let { shownThisSession[it] = (shownThisSession[it] ?: 1) - 1 }
                mountedSeq = null
                mountedId = null
            }
            if (!presentable || ticket == null || ticket.seq == mountedSeq || gates.value.isNotEmpty()) return After.CONTINUE
            mountedSeq = ticket.seq
            mountedId = ticket.id
            if (ticket.id == target) showDays += day
            shownThisSession[ticket.id] = (shownThisSession[ticket.id] ?: 0) + 1
            val distinct = shownThisSession.filterValues { it > 0 }.keys
            if (distinct.size > 1) fail("seed=$seed target=$target day=$day two prompts in one session: $distinct")
            coordinator.onShown(ticket)
            if (ticket.viaSystemRequest) return systemRequest(ticket)
            val behaviour = if (ticket.id == PromptId.LEGACY_MIGRATION) Behaviour.SETTINGS else pickBehaviour()
            return when (behaviour) {
                Behaviour.DECLINE, Behaviour.OUTSIDE -> {
                    coordinator.onDeclined(ticket)
                    unmount()
                    After.CONTINUE
                }
                Behaviour.IGNORE -> After.END_SESSION
                Behaviour.SETTINGS -> {
                    coordinator.onActionTaken(ticket)
                    unmount()
                    After.END_SESSION
                }
                Behaviour.RECREATE -> {
                    presentable = false
                    report()
                    advance(300)
                    presentable = true
                    report()
                    coordinator.onShown(ticket)
                    coordinator.onDeclined(ticket)
                    unmount()
                    After.CONTINUE
                }
                Behaviour.KILL -> After.KILL
                Behaviour.KEEP_OPEN -> After.CONTINUE
            }
        }

        private suspend fun systemRequest(ticket: PromptTicket): After {
            coordinator.onActionTaken(ticket, rationaleBefore = systemDenials == 1)
            unmount()
            presentable = false
            report()
            // The user denies whenever the system asks; after two denials it answers silently.
            val rationaleAfter = if (systemDenials >= 2) {
                false
            } else {
                systemDenials++
                systemDenials == 1
            }
            advance(TICK_MS)
            coordinator.onNotificationRequestResult(granted = false, rationaleAfter = rationaleAfter)
            test.runCurrent()
            return After.CONTINUE
        }

        private fun unmount() {
            mountedSeq = null
            mountedId = null
        }

        private fun pickBehaviour(): Behaviour {
            var roll = random.nextInt(BEHAVIOUR_WEIGHTS.values.sum())
            for ((behaviour, weight) in BEHAVIOUR_WEIGHTS) {
                if (roll < weight) return behaviour
                roll -= weight
            }
            return Behaviour.DECLINE
        }

        private fun assertWindows() {
            assertTrue("seed=$seed target=$target never shown", showDays.isNotEmpty())
            for (start in 0L..(DAYS - WINDOW_DAYS)) {
                val window = start until start + WINDOW_DAYS
                if (showDays.none { it in window }) {
                    fail("seed=$seed target=$target noise=$noise not shown in days $window; shown on $showDays")
                }
            }
        }
    }

    /** Latest value only, no write log: the simulation writes for 90 days. Writers run on the test scheduler, one at a time. */
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> get() = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private companion object {
        val TRANSIENT_GATES = listOf(GateId.SECURITY, GateId.INACTIVE_DEVICE, GateId.NETWORK_RISK)
        val SEEDS = listOf(1L, 7L, 42L, 1355L, 20261019L) + (100L until 110L)
        const val DAYS = 90L
        const val WINDOW_DAYS = 31L
        const val MAX_SESSIONS_PER_DAY = 6
        const val DAY_MS = 86_400_000L
        const val BASE = 1_000 * DAY_MS
        const val TICK_MS = 1_000L
        const val SETTLE_MS = 600L
        val BEHAVIOUR_WEIGHTS = linkedMapOf(
            Behaviour.DECLINE to 30,
            Behaviour.OUTSIDE to 10,
            Behaviour.IGNORE to 15,
            Behaviour.SETTINGS to 20,
            Behaviour.RECREATE to 10,
            Behaviour.KILL to 5,
            Behaviour.KEEP_OPEN to 10,
        )
    }
}
