package com.difft.android.call.ui

import android.os.Looper
import com.difft.android.base.call.CallType
import com.difft.android.call.CallIntent
import com.difft.android.call.data.CallStatus
import com.difft.android.call.handler.RoomEventDispatcherFixture
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.test.rules.GlobalStaticMockRule
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Framework pin for the share-start chrome reveal (row T3-26): the session sets `isShareScreening`
 * and emits `RevealCallChrome`, and the VM's `viewModelScope` collector (`Main.immediate`) applies
 * the reveal in the same main-thread message, with no Looper post in between. The main Looper is
 * paused, so anything posted would still be pending when the assertions run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MainImmediateSameMessageTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    private val roomEventScope = CoroutineScope(Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        roomEventScope.cancel()
        unmockkAll()
    }

    @Test
    fun `T3-26 share start lands the flag and both chrome reveals before the main Looper runs again`() {
        CallVmTestHarness.mockConstructionCollaborators()
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.GROUP.type)
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.JOIN_CALL, CallType.GROUP.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)
        val session = CallVmTestHarness.lastHarness.sessions.last()
        val ui = vm.callUiController
        ui.toggleTopBottomBars()
        assertEquals(false, ui.showTopStatusViewEnabled.value)

        val f = RoomEventDispatcherFixture(roomEventScope, CallVmTestHarness.lastHarness.uiWriter(session))
        val dispatcher = f.build(CallType.GROUP.type)
        shadowOf(Looper.getMainLooper()).pause()

        // The room-event collector body, run as it is in production: inside a Main.immediate coroutine.
        roomEventScope.launch { f.dispatch(dispatcher, trackSubscribed(f, sharer())) }

        assertEquals(true, ui.isShareScreening.value)
        assertEquals(true, ui.showTopStatusViewEnabled.value)
        assertEquals(true, ui.showBottomToolBarViewEnabled.value)
    }

    private fun sharer(): RemoteParticipant = mockk(relaxed = true) {
        every { identity } returns Participant.Identity("alice.1")
        every { getTrackPublication(Track.Source.SCREEN_SHARE) } returns mockk<RemoteTrackPublication>(relaxed = true)
    }

    private fun trackSubscribed(f: RoomEventDispatcherFixture, participant: RemoteParticipant): RoomEvent.TrackSubscribed {
        val publication = mockk<RemoteTrackPublication>(relaxed = true) {
            every { source } returns Track.Source.SCREEN_SHARE
        }
        return RoomEvent.TrackSubscribed(f.room, mockk(relaxed = true), publication, participant)
    }
}
