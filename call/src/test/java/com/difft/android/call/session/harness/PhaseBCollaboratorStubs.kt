package com.difft.android.call.session.harness

import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.handler.CriticalAlertDispatcher
import com.difft.android.call.handler.RoomEventDispatcher
import com.difft.android.call.media.CallMediaController
import com.difft.android.call.network.NetworkQualityCoordinator
import com.difft.android.call.session.CallObservers
import com.difft.android.call.session.CallSessionStarter
import com.difft.android.call.session.CallTypeCoordinator
import io.livekit.android.room.MediaSendConnectionState
import io.livekit.android.room.Room
import io.livekit.android.util.flow
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KProperty0

/**
 * Member stubs for the collaborators Phase B builds inline. `mockkConstructor` runs the real
 * constructor (none of these touches `android.*` while constructing) and then swaps member
 * dispatch, so ordering and side effects can be verified on the plain JVM.
 */
internal object PhaseBCollaboratorStubs {

    /** The network-quality coordinator handed out by the stubbed factory (its real `start` asserts the main Looper). */
    lateinit var networkQuality: NetworkQualityCoordinator
        private set

    fun install() {
        // LiveKit's @FlowObservable delegates do not exist on a mock Room: hand out typed flows by property name.
        mockkStatic("io.livekit.android.util.FlowDelegateKt")
        every { any<KProperty0<Any?>>().flow } answers {
            when (firstArg<KProperty0<*>>().name) {
                "state" -> MutableStateFlow(Room.State.DISCONNECTED)
                "mediaSendConnectionState" -> MutableStateFlow(MediaSendConnectionState.entries.first())
                else -> MutableStateFlow(emptyList<Any>())
            }
        }

        mockkConstructor(CallMediaController::class)
        every { anyConstructed<CallMediaController>().setVoicePreset(any()) } just Runs
        every { anyConstructed<CallMediaController>().deNoiseEnable } returns MutableStateFlow(true)
        every { anyConstructed<CallMediaController>().voicePreset } returns MutableStateFlow(VoicePreset.ORIGINAL)

        mockkConstructor(CallTypeCoordinator::class)
        every { anyConstructed<CallTypeCoordinator>().start() } just Runs

        mockkConstructor(RoomEventDispatcher::class)
        every { anyConstructed<RoomEventDispatcher>().startCollectingRoomEvents() } just Runs
        every { anyConstructed<RoomEventDispatcher>().startCollectingParticipants() } just Runs
        every { anyConstructed<RoomEventDispatcher>().cancelJobs() } just Runs

        mockkConstructor(CallSessionStarter::class)
        every { anyConstructed<CallSessionStarter>().start() } just Runs

        mockkConstructor(CallConnectionCoordinator::class)
        every {
            anyConstructed<CallConnectionCoordinator>().observeManualSwitchReconnect(any(), any(), any(), any())
        } returns mockk(relaxed = true)

        mockkConstructor(CriticalAlertDispatcher::class)
        every { anyConstructed<CriticalAlertDispatcher>().refreshGroupStatus(any()) } just Runs

        networkQuality = mockk(relaxed = true)
        mockkObject(NetworkQualityCoordinator.Companion)
        every { NetworkQualityCoordinator.create(any(), any(), any(), any()) } returns networkQuality

        mockkObject(CallObservers)
        every {
            CallObservers.register(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } just Runs
    }
}
