package com.difft.android.call.session.harness

import android.util.Log
import com.difft.android.base.call.CallType
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.RoomMetadata
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.manager.ProximityScreenOffController
import com.difft.android.call.session.CallAudioStack
import com.difft.android.call.session.CallSessionComponentsFactory
import com.difft.android.call.session.CallSessionParams
import com.github.TempTalkOrg.audio_pipeline.AudioModule
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.Room
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Test [CallSessionComponentsFactory]: every native / framework piece is a mock, and the room
 * controller's observable state is driven from the public fields below.
 */
internal class FakeCallSessionComponentsFactory : CallSessionComponentsFactory {
    val callStatus = MutableStateFlow(CallStatus.CALLING)
    val callType = MutableStateFlow(CallType.ONE_ON_ONE.type)
    val errors = Channel<Throwable>(Channel.BUFFERED)
    val micEnabled = MutableStateFlow(false)

    /** Flipped by tests to hit the Phase B abort points. */
    @Volatile var releaseIntended = false

    /** Value of `createRoom()` and the fail-loud `room` getter. */
    var room: Room = mockk(relaxed = true)

    /** When true the fail-loud `room` getter throws, as it does once the room is released. */
    @Volatile var roomGetterThrows = false

    /** When true `roomOrNull()` reads null, as it does once the room is released. */
    @Volatile var roomReleased = false
    var roomState: Room.State? = Room.State.CONNECTED
    var createRoomCalls = 0
        private set

    @Volatile var createRoomThread: Thread? = null
        private set
    var cameraRegistrations = 0
        private set

    init {
        // AudioPipelineProcessor's static init fails to load its native library on the JVM and
        // reports that through android.util.Log.e, an unmocked android.jar stub here.
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
    }

    val audio = CallAudioStack(
        processor = mockk(relaxed = true),
        deviceManager = mockk(relaxed = true) {
            every { deNoiseEnable } returns MutableStateFlow(true)
            every { deNoiseMode } returns MutableStateFlow(AudioModule.RNNOISE)
            every { voicePreset } returns MutableStateFlow(VoicePreset.ORIGINAL)
        },
        routeApplier = mockk(relaxed = true),
        setup = mockk(relaxed = true),
    )

    val roomCtl: CallRoomController = mockk(relaxed = true) {
        every { this@mockk.callStatus } returns this@FakeCallSessionComponentsFactory.callStatus
        every { this@mockk.callType } returns this@FakeCallSessionComponentsFactory.callType
        every { error } returns errors.receiveAsFlow()
        every { micEnabled } returns this@FakeCallSessionComponentsFactory.micEnabled
        every { cameraEnabled } returns MutableStateFlow(false)
        every { isNoSpeakSoloTimeout } returns MutableStateFlow(false)
        every { roomMetadata } returns MutableStateFlow(RoomMetadata(canPublishAudio = true, canPublishVideo = true))
        every { createRoom() } answers {
            synchronized(this@FakeCallSessionComponentsFactory) { createRoomCalls++ }
            createRoomThread = Thread.currentThread()
            this@FakeCallSessionComponentsFactory.room
        }
        every { this@mockk.room } answers {
            check(!roomGetterThrows) { "[Call] room accessed after release" }
            this@FakeCallSessionComponentsFactory.room
        }
        every { isReleaseIntended() } answers { releaseIntended }
        every { roomOrNull() } answers { this@FakeCallSessionComponentsFactory.room.takeIf { createRoomCalls > 0 && !roomReleased } }
        every { roomStateOrNull() } answers { roomState }
    }

    override fun audioStack(params: CallSessionParams, scope: CoroutineScope, isDenoiseEnabled: () -> Boolean) = audio

    override fun roomController(
        params: CallSessionParams,
        audioHandler: AudioSwitchHandler,
        audioProcessor: AudioPipelineProcessor,
    ) = roomCtl

    override fun selfId() = SELF_ID

    override fun registerCameraProvider(scope: CoroutineScope) {
        cameraRegistrations++
    }

    /** The session's proximity lock: a recording mock (it reaches the power manager on device). */
    val proximity: ProximityScreenOffController = mockk(relaxed = true)

    override fun proximity(scope: CoroutineScope, shouldHold: Flow<Boolean>) = proximity

    companion object {
        const val SELF_ID = "self-uid"
    }
}
