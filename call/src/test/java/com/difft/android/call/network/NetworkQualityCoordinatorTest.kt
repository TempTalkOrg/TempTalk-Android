package com.difft.android.call.network

import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.data.MediaSendIssueState
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.CallSessionUiState
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
    private lateinit var uiState: CallSessionUiState

    private var localQuality: ConnectionQuality = ConnectionQuality.EXCELLENT
    private var remoteQualities: Map<String, ConnectionQuality> = emptyMap()

    



    private val published = mutableListOf<NetworkQualityView>()

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        scope = CoroutineScope(dispatcherRule.testDispatcher)
        roomState = MutableStateFlow(Room.State.DISCONNECTED)
        mediaSendIssue = MutableStateFlow(MediaSendIssueState.NONE)
        uiState = CallSessionUiState()
        localQuality = ConnectionQuality.EXCELLENT
        remoteQualities = emptyMap()
        scope.launch { uiState.networkQuality.collect { published += it } }
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
        uiState = uiState,
        nowProvider = { scheduler.currentTime },
    )

    


    private fun coordinatorWithDefaultClock() = NetworkQualityCoordinator(
        scope = scope,
        roomState = roomState,
        mediaSendIssue = mediaSendIssue,
        localQualityProvider = { localQuality },
        remoteQualityProvider = { remoteQualities },
        uiState = uiState,
    )

    
    private fun advanceTo(target: Long) {
        scheduler.advanceTimeBy(target - scheduler.currentTime)
        scheduler.runCurrent()
    }

    private fun view() = uiState.networkQuality.value

    
    private fun startAndSettle(): NetworkQualityCoordinator =
        coordinator().also {
            it.start()
            scheduler.runCurrent()
        }

    
    
    
    @Test
    fun `mounting into an already connected room seeds from the providers`() {
        roomState.value = Room.State.CONNECTED
        localQuality = ConnectionQuality.POOR

        startAndSettle()
        advanceTo(3_000)

        assertTrue("local POOR seeded at mount must publish after the 3s delay", view().localIsBad)
        assertEquals(NetworkQualityLevel.BAD, view().local)
    }

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

        
        
        
        roomState.value = Room.State.CONNECTED
        scheduler.runCurrent()
        assertFalse("the pre-outage verdict must not come back on reconnect", view().localIsBad)

        advanceTo(7_900)
        assertFalse("2.9s after reconnect is still inside the worsening delay", view().localIsBad)
        advanceTo(8_000)
        assertTrue("a still-bad link lights up again 3s after reconnect", view().localIsBad)
    }

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

    @Test
    fun `local reading with a null identity still lands on the local entry`() {
        roomState.value = Room.State.CONNECTED
        val coordinator = startAndSettle()

        coordinator.onQualityChanged(identity = null, quality = ConnectionQuality.POOR, isLocal = true)
        advanceTo(3_000)

        assertEquals(NetworkQualityLevel.BAD, view().local)
        assertTrue("the local entry must never appear as a remote", view().remote.isEmpty())
    }

    @Test
    fun `remote reading is keyed by its identity`() {
        roomState.value = Room.State.CONNECTED
        val coordinator = startAndSettle()

        coordinator.onQualityChanged(identity = "+123.1", quality = ConnectionQuality.POOR, isLocal = false)
        advanceTo(3_000)

        assertEquals(NetworkQualityLevel.BAD, view().remote["+123.1"])
        assertEquals(NetworkQualityLevel.EXCELLENT, view().local)
    }

    @Test
    fun `remote reading without an identity is dropped`() {
        roomState.value = Room.State.CONNECTED
        val coordinator = startAndSettle()

        coordinator.onQualityChanged(identity = null, quality = ConnectionQuality.POOR, isLocal = false)
        advanceTo(3_000)

        assertEquals("an unkeyable remote reading can be neither stored nor rendered", NetworkQualityView.NONE, view())
    }

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

        
        
        coordinator.start()
        scheduler.runCurrent()
        advanceTo(16_000)
        assertTrue("start() after stop() must seed and tick again", view().localIsBad)
    }

    
    
    
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

        
        mediaSendIssue.value = MediaSendIssueState.NONE
        scheduler.runCurrent()
        assertFalse("the pre-recovery verdict must not come back instantly", view().localIsBad)

        advanceTo(15_900)
        assertFalse("2.9s after recovery is still inside the worsening delay", view().localIsBad)
        advanceTo(16_000)
        assertTrue("a still-bad link lights up again 3s later", view().localIsBad)
    }

    @Test
    fun `an uplink-only issue does not suppress the verdict`() {
        roomState.value = Room.State.CONNECTED
        localQuality = ConnectionQuality.POOR
        startAndSettle()
        advanceTo(3_000)
        assertTrue(view().localIsBad)

        
        
        mediaSendIssue.value = MediaSendIssueState.SEND_RECOVERING
        scheduler.runCurrent()
        advanceTo(20_000)

        assertTrue("an uplink issue is not a suppression input", view().localIsBad)
    }

    @Test
    fun `mounting into an already recovering link stays empty`() {
        
        
        roomState.value = Room.State.CONNECTED
        mediaSendIssue.value = MediaSendIssueState.CONNECTION_RECOVERING
        localQuality = ConnectionQuality.POOR

        startAndSettle()
        advanceTo(13_000)

        assertEquals(NetworkQualityView(suppressed = true), view())
    }

    
    
    @Test
    fun `hysteresis follows the monotonic clock and not the wall clock`() {
        roomState.value = Room.State.CONNECTED
        localQuality = ConnectionQuality.POOR
        val coordinator = coordinatorWithDefaultClock()
        coordinator.start()
        scheduler.runCurrent()

        
        
        advanceTo(2_000)
        assertFalse("four ticks with a frozen clock must not satisfy the 3s delay", view().localIsBad)

        
        
        
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        advanceTo(2_500)
        assertTrue("elapsedRealtime passing the 3s delay must publish", view().localIsBad)
    }
}
