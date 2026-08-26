package com.difft.android.call.network

import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.MediaSendIssueState
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Host-layer coverage for [NetworkQualityCoordinator]. The verdict state machine itself is covered by
 * the pure-JVM `NetworkQualityTrackerTest`; what is asserted here is only what the host owns: the
 * drive sources (room events, room state, the merged send state, the 500 ms tick), the union of the
 * two suppression inputs, the re-seed ordering, the reverse cleanup, teardown, and the monotonic
 * clock.
 *
 * Robolectric rather than a plain JVM test because the coordinator logs through `L` and because C9
 * drives the real `SystemClock`.
 *
 * Virtual time: `nowProvider` reads the same [StandardTestDispatcher] scheduler the tick's `delay`
 * uses, so hysteresis boundaries land on exact millisecond values. C9 is the deliberate exception —
 * it must exercise the production default provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkQualityCoordinatorTest {

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(StandardTestDispatcher())

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private val scheduler get() = dispatcherRule.testDispatcher.scheduler

    private lateinit var scope: CoroutineScope
    private lateinit var roomState: MutableStateFlow<Room.State>
    private lateinit var mediaSendIssue: MutableStateFlow<MediaSendIssueState>
    private lateinit var controller: CallUiController

    private var localQuality: ConnectionQuality = ConnectionQuality.EXCELLENT
    private var remoteQualities: Map<String, ConnectionQuality> = emptyMap()

    /**
     * Every distinct snapshot the controller published, in order. Stands in for Turbine's
     * `expectNoEvents()`: asserting the recorded size is unchanged after advancing virtual time is
     * the same statement ("the tick really stopped"), and it stays deterministic under explicit
     * virtual-time control.
     */
    private val published = mutableListOf<NetworkQualityView>()

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        scope = CoroutineScope(dispatcherRule.testDispatcher)
        roomState = MutableStateFlow(Room.State.DISCONNECTED)
        mediaSendIssue = MutableStateFlow(MediaSendIssueState.NONE)
        controller = CallUiController()
        localQuality = ConnectionQuality.EXCELLENT
        remoteQualities = emptyMap()
        scope.launch { controller.networkQuality.collect { published += it } }
        scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        scope.cancel()
        published.clear()
        unmockkAll()
    }

    private fun coordinator() = NetworkQualityCoordinator(
        scope = scope,
        roomState = roomState,
        mediaSendIssue = mediaSendIssue,
        localQualityProvider = { localQuality },
        remoteQualityProvider = { remoteQualities },
        callUiController = controller,
        nowProvider = { scheduler.currentTime },
    )

    /** C9 only: exercises the production default `nowProvider` (SystemClock.elapsedRealtime). */
    private fun coordinatorWithDefaultClock() = NetworkQualityCoordinator(
        scope = scope,
        roomState = roomState,
        mediaSendIssue = mediaSendIssue,
        localQualityProvider = { localQuality },
        remoteQualityProvider = { remoteQualities },
        callUiController = controller,
    )

    /** Advances virtual time to exactly [target] and runs the task scheduled AT that instant. */
    private fun advanceTo(target: Long) {
        scheduler.advanceTimeBy(target - scheduler.currentTime)
        scheduler.runCurrent()
    }

    private fun view() = controller.networkQuality.value

    /** Starts the coordinator and lets the room-state collector process the current value. */
    private fun startAndSettle(): NetworkQualityCoordinator =
        coordinator().also {
            it.start()
            scheduler.runCurrent()
        }

    // ------------------------------------------------------------------ C1
    // Mid-call mount: the room is ALREADY connected when start() runs, so the seed must come from
    // the providers rather than from a state transition (a coordinator that only seeds on a
    // false->true suppression flip would show nothing here).
    @Test
    fun `mounting into an already connected room seeds from the providers`() {
        roomState.value = Room.State.CONNECTED
        localQuality = ConnectionQuality.POOR

        startAndSettle()
        advanceTo(3_000)

        assertTrue("local POOR seeded at mount must publish after the 3s delay", view().localIsBad)
        assertEquals(NetworkQualityLevel.BAD, view().local)
    }

    // ------------------------------------------------------------------ C2
    @Test
    fun `worsening publishes on the tick at 3000 not at 2500`() {
        roomState.value = Room.State.CONNECTED
        val coordinator = startAndSettle()

        coordinator.onQualityChanged(identity = null, quality = ConnectionQuality.POOR, isLocal = true)

        advanceTo(2_500)
        assertFalse("2.5s of POOR must not publish yet", view().localIsBad)
        advanceTo(3_000)
        assertTrue("3.0s of POOR must publish", view().localIsBad)
    }

    // ------------------------------------------------------------------ C3
    @Test
    fun `leaving CONNECTED empties the view and stops the tick`() {
        roomState.value = Room.State.CONNECTED
        val coordinator = startAndSettle()
        coordinator.onQualityChanged(identity = null, quality = ConnectionQuality.POOR, isLocal = true)
        advanceTo(3_000)
        assertTrue(view().localIsBad)

        roomState.value = Room.State.RECONNECTING
        scheduler.runCurrent()

        assertEquals(NetworkQualityView(suppressed = true), view())
        val publishedCount = published.size
        advanceTo(13_000)
        assertEquals("the tick must be cancelled while suppressed", publishedCount, published.size)
    }

    // ------------------------------------------------------------------ C4
    @Test
    fun `reconnecting restarts the timers instead of republishing the stale verdict`() {
        roomState.value = Room.State.CONNECTED
        localQuality = ConnectionQuality.POOR
        startAndSettle()
        advanceTo(3_000)
        assertTrue(view().localIsBad)

        roomState.value = Room.State.RECONNECTING
        scheduler.runCurrent()
        advanceTo(5_000)

        // The SDK's cached local connectionQuality is still POOR across the outage, so the re-seed
        // feeds the same reading back in. Clearing suppression BEFORE seeding is what stops it from
        // republishing instantly.
        roomState.value = Room.State.CONNECTED
        scheduler.runCurrent()
        assertFalse("the pre-outage verdict must not come back on reconnect", view().localIsBad)

        advanceTo(7_900)
        assertFalse("2.9s after reconnect is still inside the worsening delay", view().localIsBad)
        advanceTo(8_000)
        assertTrue("a still-bad link lights up again 3s after reconnect", view().localIsBad)
    }

    // ------------------------------------------------------------------ C5
    @Test
    fun `reseed prunes remotes that are no longer in the room`() {
        roomState.value = Room.State.CONNECTED
        remoteQualities = mapOf("stale" to ConnectionQuality.POOR)
        startAndSettle()
        advanceTo(3_000)
        assertEquals(setOf("stale"), view().badRemoteIdentities)

        roomState.value = Room.State.RECONNECTING
        scheduler.runCurrent()
        val firstIndexAfterOutage = published.size

        remoteQualities = mapOf("bob" to ConnectionQuality.EXCELLENT)
        roomState.value = Room.State.CONNECTED
        scheduler.runCurrent()
        advanceTo(13_000)

        assertTrue(
            "retainRemotes must run after seeding, dropping a member the room no longer has",
            published.drop(firstIndexAfterOutage).none { "stale" in it.remote },
        )
        assertFalse("the departed member must be gone from the final snapshot too", "stale" in view().remote)
        assertTrue(view().badRemoteIdentities.isEmpty())
    }

    // ------------------------------------------------------------------ C6
    @Test
    fun `participant left publishes immediately without waiting for a tick`() {
        roomState.value = Room.State.CONNECTED
        remoteQualities = mapOf("alice" to ConnectionQuality.POOR)
        val coordinator = startAndSettle()
        advanceTo(3_000)
        assertEquals(setOf("alice"), view().badRemoteIdentities)

        coordinator.onParticipantLeft("alice")

        assertTrue("a leaving participant's badge must clear now, not 500ms later", view().badRemoteIdentities.isEmpty())
    }

    // ------------------------------------------------------------------ C7a
    @Test
    fun `local reading with a null identity still lands on the local entry`() {
        roomState.value = Room.State.CONNECTED
        val coordinator = startAndSettle()

        coordinator.onQualityChanged(identity = null, quality = ConnectionQuality.POOR, isLocal = true)
        advanceTo(3_000)

        assertEquals(NetworkQualityLevel.BAD, view().local)
        assertTrue("the local entry must never appear as a remote", view().remote.isEmpty())
    }

    // ------------------------------------------------------------------ C7b
    @Test
    fun `remote reading is keyed by its identity`() {
        roomState.value = Room.State.CONNECTED
        val coordinator = startAndSettle()

        coordinator.onQualityChanged(identity = "+123.1", quality = ConnectionQuality.POOR, isLocal = false)
        advanceTo(3_000)

        assertEquals(NetworkQualityLevel.BAD, view().remote["+123.1"])
        assertEquals(NetworkQualityLevel.EXCELLENT, view().local)
    }

    // ------------------------------------------------------------------ C7c
    @Test
    fun `remote reading without an identity is dropped`() {
        roomState.value = Room.State.CONNECTED
        val coordinator = startAndSettle()

        coordinator.onQualityChanged(identity = null, quality = ConnectionQuality.POOR, isLocal = false)
        advanceTo(3_000)

        assertEquals("an unkeyable remote reading can be neither stored nor rendered", NetworkQualityView.NONE, view())
    }

    // ------------------------------------------------------------------ C8
    @Test
    fun `stop clears the snapshot and start is re-entrant`() {
        roomState.value = Room.State.CONNECTED
        localQuality = ConnectionQuality.POOR
        val coordinator = startAndSettle()
        advanceTo(3_000)
        assertTrue(view().localIsBad)

        coordinator.stop()
        assertEquals(NetworkQualityView.NONE, view())
        scheduler.runCurrent()
        val publishedCount = published.size
        advanceTo(13_000)
        assertEquals("both jobs must be cancelled by stop()", publishedCount, published.size)

        // The Room object survives a call, so a second call must be able to restart the same
        // coordinator instance from a clean tracker.
        coordinator.start()
        scheduler.runCurrent()
        advanceTo(16_000)
        assertTrue("start() after stop() must seed and tick again", view().localIsBad)
    }

    // ------------------------------------------------------------------ C13
    // Whole-link recovery suppresses even though the room state itself never leaves CONNECTED. An
    // implementation that judges suppression from `room.state` alone passes every case above and
    // still leaves the hint on screen through an outage the user is already being told about.
    @Test
    fun `link recovery suppresses the view while the room state stays connected`() {
        roomState.value = Room.State.CONNECTED
        localQuality = ConnectionQuality.POOR
        startAndSettle()
        advanceTo(3_000)
        assertTrue(view().localIsBad)

        mediaSendIssue.value = MediaSendIssueState.CONNECTION_RECOVERING
        scheduler.runCurrent()

        assertEquals(
            "the room never left CONNECTED, yet the snapshot must be emptied",
            NetworkQualityView(suppressed = true),
            view(),
        )
        val publishedCount = published.size
        advanceTo(13_000)
        assertEquals("the tick must be cancelled while suppressed", publishedCount, published.size)

        // Leaving recovery re-seeds and restarts the delay, exactly like leaving a room outage.
        mediaSendIssue.value = MediaSendIssueState.NONE
        scheduler.runCurrent()
        assertFalse("the pre-recovery verdict must not come back instantly", view().localIsBad)

        advanceTo(15_900)
        assertFalse("2.9s after recovery is still inside the worsening delay", view().localIsBad)
        advanceTo(16_000)
        assertTrue("a still-bad link lights up again 3s later", view().localIsBad)
    }

    // ------------------------------------------------------------------ C14
    @Test
    fun `an uplink-only issue does not suppress the verdict`() {
        roomState.value = Room.State.CONNECTED
        localQuality = ConnectionQuality.POOR
        startAndSettle()
        advanceTo(3_000)
        assertTrue(view().localIsBad)

        // SEND_RECOVERING outranks the weak-network hint in the pill, but the verdict underneath must
        // keep being maintained — otherwise the hint would re-earn its 3 s once the uplink recovers.
        mediaSendIssue.value = MediaSendIssueState.SEND_RECOVERING
        scheduler.runCurrent()
        advanceTo(20_000)

        assertTrue("an uplink issue is not a suppression input", view().localIsBad)
    }

    // ------------------------------------------------------------------ C15
    @Test
    fun `mounting into an already recovering link stays empty`() {
        // Both suppression halves are primed before either collector runs, so a mid-call mount can
        // never seed a snapshot that the sibling flow immediately empties.
        roomState.value = Room.State.CONNECTED
        mediaSendIssue.value = MediaSendIssueState.CONNECTION_RECOVERING
        localQuality = ConnectionQuality.POOR

        startAndSettle()
        advanceTo(13_000)

        assertEquals(NetworkQualityView(suppressed = true), view())
    }

    // ------------------------------------------------------------------ C9 (framework assumption)
    // The hysteresis MUST be driven by the monotonic clock (SystemClock.elapsedRealtime). A wall
    // clock jumps backwards on time sync — a hint would stick on screen forever — and forwards on
    // sleep/wake, skipping the delay altogether. This is the only executable guard on that choice,
    // so it runs against the production default nowProvider, not an injected one.
    @Test
    fun `hysteresis follows the monotonic clock and not the wall clock`() {
        roomState.value = Room.State.CONNECTED
        localQuality = ConnectionQuality.POOR
        val coordinator = coordinatorWithDefaultClock()
        coordinator.start()
        scheduler.runCurrent()

        // (a) Ticks alone must not fill the delay: the clock is the source of truth, never the tick
        // count or the coroutine scheduler's own time.
        advanceTo(2_000)
        assertFalse("four ticks with a frozen clock must not satisfy the 3s delay", view().localIsBad)

        // (b) This advances elapsedRealtime by 3s while leaving the process wall clock
        // (System.currentTimeMillis) untouched, so it publishes only if the provider really is the
        // monotonic clock: a currentTimeMillis implementation would still measure ~0 ms elapsed.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        advanceTo(2_500)
        assertTrue("elapsedRealtime passing the 3s delay must publish", view().localIsBad)
    }
}
