package com.difft.android.call.media

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.RoomMetadata
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.service.TestScopeApplication
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.track.TrackPublication
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Last-line permission gates in [CallMediaController] (spec: "无权限启用不进入 LiveKit"、
 * "关闭操作不查权限"). Robolectric denies all runtime permissions by default; grant cases
 * call `grantPermissions` explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallMediaControllerPermissionGateTest {

    private lateinit var room: Room
    private lateinit var localParticipant: LocalParticipant
    private lateinit var roomCtl: CallRoomController
    private lateinit var controller: CallMediaController
    private val scope = TestScope(UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext())
        room = mockk(relaxed = true)
        localParticipant = mockk(relaxed = true)
        roomCtl = mockk(relaxed = true)
        every { room.localParticipant } returns localParticipant
        every { roomCtl.roomMetadata } returns MutableStateFlow(RoomMetadata())
        controller = CallMediaController(
            room = room,
            roomCtl = roomCtl,
            audioProcessor = mockk<AudioPipelineProcessor>(relaxed = true),
            audioDeviceManager = mockk<AudioDeviceManager>(relaxed = true),
            scope = scope,
            showBarrage = { _, _ -> },
            showToast = { },
        )
    }

    @After
    fun tearDown() = unmockkAll()

    private fun grant(vararg permissions: String) {
        shadowOf(ApplicationProvider.getApplicationContext<TestScopeApplication>()).grantPermissions(*permissions)
    }

    // ------------------------------------------------------------------
    // Camera gate
    // ------------------------------------------------------------------

    @Test
    fun `camera enable without permission never reaches LiveKit and resets the toggle state`() {
        controller.setCameraEnabled(true)

        coVerify(exactly = 0) { localParticipant.setCameraEnabled(any()) }
        verify { roomCtl.updateCameraEnabled(false) }
    }

    @Test
    fun `camera enable with permission reaches LiveKit`() {
        grant(Manifest.permission.CAMERA)
        every { localParticipant.videoTrackPublications } returns emptyList()

        controller.setCameraEnabled(true)

        // setCamera hops to Dispatchers.IO (hardcoded), so wait instead of asserting inline.
        coVerify(timeout = 2000) { localParticipant.setCameraEnabled(true) }
        verify(timeout = 2000) { roomCtl.updateCameraEnabled(true) }
    }

    @Test
    fun `camera disable without permission still reaches LiveKit`() {
        val publication = mockk<TrackPublication>(relaxed = true)
        every { localParticipant.videoTrackPublications } returns listOf(publication to null)

        controller.setCameraEnabled(false)

        coVerify(timeout = 2000) { localParticipant.setCameraEnabled(false) }
    }

    // ------------------------------------------------------------------
    // Mic gate
    // ------------------------------------------------------------------

    @Test
    fun `mic enable without permission never reaches LiveKit and resets the toggle state`() {
        controller.setMicEnabled(true)

        coVerify(exactly = 0) { localParticipant.setMicrophoneEnabled(any(), any()) }
        verify { roomCtl.updateMicEnabled(false) }
    }

    @Test
    fun `mic silence pre-publish without permission is also blocked`() {
        controller.setMicEnabled(true, publishMuted = true, isShowBarrage = false)

        coVerify(exactly = 0) { localParticipant.setMicrophoneEnabled(any(), any()) }
    }

    @Test
    fun `mic enable with permission reaches LiveKit`() {
        grant(Manifest.permission.RECORD_AUDIO)
        every { localParticipant.audioTrackPublications } returns emptyList()

        controller.setMicEnabled(true, isShowBarrage = false)

        coVerify(timeout = 2000) { localParticipant.setMicrophoneEnabled(true, false) }
        verify(timeout = 2000) { roomCtl.updateMicEnabled(true) }
    }

    @Test
    fun `mic disable without permission still reaches LiveKit`() {
        val publication = mockk<TrackPublication>(relaxed = true)
        every { localParticipant.audioTrackPublications } returns listOf(publication to null)

        controller.setMicEnabled(false, isShowBarrage = false)

        coVerify(timeout = 2000) { localParticipant.setMicrophoneEnabled(false, false) }
    }
}
