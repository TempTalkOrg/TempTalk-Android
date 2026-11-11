package com.difft.android.call.handler

import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.data.CallStatus
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config







@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class RoomEventDispatcherConnectionQualityTest {

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule()

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var f: RoomEventDispatcherFixture
    private val room get() = f.room
    private val roomCtl get() = f.roomCtl
    private val timeoutMonitor get() = f.timeoutMonitor
    private val networkQuality get() = f.networkQuality

    
    private val networkPoorCalls get() = f.networkPoorCalls

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        f = RoomEventDispatcherFixture(CoroutineScope(dispatcherRule.testDispatcher))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildDispatcher(): RoomEventDispatcher = f.build(CallType.ONE_ON_ONE.type)

    private fun dispatch(dispatcher: RoomEventDispatcher, event: RoomEvent) = f.dispatch(dispatcher, event)

    private fun localParticipant(): LocalParticipant = mockk<LocalParticipant>(relaxed = true).also {
        every { it.identity } returns Participant.Identity("self.1")
    }

    private fun remoteParticipant(identity: String = "+123.1"): RemoteParticipant =
        mockk<RemoteParticipant>(relaxed = true).also {
            every { it.identity } returns Participant.Identity(identity)
        }

    private fun qualityEvent(participant: Participant, quality: ConnectionQuality) =
        RoomEvent.ConnectionQualityChanged(room, participant, quality)

    
    @Test
    fun `remote quality feeds the verdict unit without touching the rating chain`() {
        val dispatcher = buildDispatcher()

        dispatch(dispatcher, qualityEvent(remoteParticipant(), ConnectionQuality.POOR))

        assertEquals(emptyList<Boolean>(), networkPoorCalls)
        verify(exactly = 1) { networkQuality.onQualityChanged("+123.1", ConnectionQuality.POOR, false) }
    }

    @Test
    fun `local quality keeps the original poor classification including UNKNOWN`() {
        val dispatcher = buildDispatcher()
        val local = localParticipant()

        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.POOR))
        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.EXCELLENT))
        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.UNKNOWN))

        
        
        
        assertEquals(listOf(true, false, true), networkPoorCalls)
        verify(exactly = 3) { networkQuality.onQualityChanged("self.1", any(), true) }
    }

    
    @Test
    fun `participant disconnected clears the verdict even while the switch guard is active`() {
        every { roomCtl.callStatus } returns MutableStateFlow(CallStatus.SWITCHING_SERVER)
        val dispatcher = buildDispatcher()

        dispatch(dispatcher, RoomEvent.ParticipantDisconnected(room, remoteParticipant("+456.1")))

        verify(exactly = 1) { networkQuality.onParticipantLeft("+456.1") }
        verify(exactly = 0) { timeoutMonitor.onParticipantDisconnected(any()) }
    }

    
    @Test
    fun `repeated local poor readings never raise a call error`() {
        val dispatcher = buildDispatcher()
        val local = localParticipant()

        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.POOR))
        dispatch(dispatcher, qualityEvent(local, ConnectionQuality.POOR))

        verify(exactly = 0) { roomCtl.collectError(any()) }
    }
}
