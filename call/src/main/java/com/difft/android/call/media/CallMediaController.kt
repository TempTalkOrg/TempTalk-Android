package com.difft.android.call.media

import android.Manifest
import android.content.Intent
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.call.R
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.manager.AudioDeviceManager
import com.github.TempTalkOrg.audio_pipeline.AudioModule
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Centralizes microphone, camera, denoise and voice-preset controls that were
 * previously scattered across `LCallViewModel`. Keeps the ViewModel focused on
 * orchestration; all direct `localParticipant.setMicrophoneEnabled/setCameraEnabled`
 * calls and `audioProcessor` wiring live here.
 */
class CallMediaController(
    private val room: Room,
    private val roomCtl: CallRoomController,
    private val audioProcessor: AudioPipelineProcessor,
    private val audioDeviceManager: AudioDeviceManager,
    private val scope: CoroutineScope,
    private val showBarrage: (Participant, String) -> Unit,
    private val showToast: (String) -> Unit,
) {

    val deNoiseEnable get() = audioDeviceManager.deNoiseEnable
    val deNoiseMode get() = audioDeviceManager.deNoiseMode
    val voicePreset get() = audioDeviceManager.voicePreset

    fun setDeNoiseEnabled(enabled: Boolean) {
        audioProcessor.setDenoiseEnabled(enabled)
        if (enabled) {
            audioProcessor.setModule(deNoiseMode.value)
        }
    }

    fun setDeNoiseMode(mode: AudioModule) {
        audioProcessor.setModule(mode)
    }

    fun setVoicePreset(preset: VoicePreset) {
        L.i { "[call] CallMediaController setVoicePreset preset=${preset.sdkKey}" }
        audioProcessor.setSoundTouchPreset(preset.sdkKey)
    }

    /**
     * Enables or disables the local microphone with optional publish-muted control.
     *
     * Last-line permission gate for EVERY enable path (toolbar tap, 1v1 auto-enable,
     * silence pre-publish, server-switch restore): without RECORD_AUDIO nothing may reach
     * `setMicrophoneEnabled(true)` — SDK 2.27.0.1 only logs a warning and would still
     * create/publish the track. Disabling is deliberately NOT gated (spec: close never
     * checks permission), and a blocked enable resets the controller state so the toggle
     * cannot render an "on" state that publishes nothing.
     */
    fun setMicEnabled(
        enabled: Boolean,
        publishMuted: Boolean = false,
        isShowBarrage: Boolean = true,
    ) {
        if (enabled && !PermissionUtil.arePermissionsGranted(ApplicationHelper.instance, arrayOf(Manifest.permission.RECORD_AUDIO))) {
            L.w { "[call] CallMediaController setMicEnabled blocked, RECORD_AUDIO missing" }
            roomCtl.updateMicEnabled(false)
            return
        }

        scope.launch {
            try {
                if (room.localParticipant.audioTrackPublications.isEmpty()) {
                    if (roomCtl.roomMetadata.value.canPublishAudio) {
                        setMicrophone(enabled, publishMuted)
                        if (isShowBarrage) showMuteBarrageByMicEnabled(enabled)
                    } else {
                        val intent = Intent(LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT)
                        intent.setPackage(ApplicationHelper.instance.packageName)
                        ApplicationHelper.instance.sendBroadcast(intent)
                    }
                } else {
                    setMicrophone(enabled, publishMuted)
                    if (isShowBarrage) showMuteBarrageByMicEnabled(enabled)
                }
            } catch (e: Exception) {
                L.e { "[call] CallMediaController setMicEnabled error:${e.message}" }
            }
        }
    }

    private fun setMicrophone(enabled: Boolean, publishMuted: Boolean) = scope.launch(Dispatchers.IO) {
        try {
            room.localParticipant.setMicrophoneEnabled(enabled, publishMuted)
            roomCtl.updateMicEnabled(enabled && !publishMuted)
        } catch (e: Throwable) {
            L.e { "[call] CallMediaController setMicrophone error:${e.message}" }
        }
    }

    private fun showMuteBarrageByMicEnabled(enabled: Boolean) {
        val message = if (enabled) getString(R.string.call_barrage_message_open_mic)
        else getString(R.string.call_barrage_message_close_mic)
        showBarrage(room.localParticipant, message)
    }

    /**
     * Enables or disables the local camera with error/permission fallback.
     *
     * Same last-line gate as [setMicEnabled]: covers the non-UI enable paths (server-switch
     * restore in RoomEventDispatcher) that skip the toolbar permission flow. Disable is not gated.
     */
    fun setCameraEnabled(enabled: Boolean) {
        if (enabled && !PermissionUtil.arePermissionsGranted(ApplicationHelper.instance, arrayOf(Manifest.permission.CAMERA))) {
            L.w { "[Call] CallMediaController setCameraEnabled blocked, CAMERA missing" }
            roomCtl.updateCameraEnabled(false)
            return
        }
        scope.launch {
            try {
                if (room.localParticipant.videoTrackPublications.isEmpty()) {
                    if (roomCtl.roomMetadata.value.canPublishVideo) {
                        if (enabled) {
                            setCamera(true)
                        }
                    } else {
                        val intent = Intent(LCallConstants.CALL_NOTIFICATION_PUSH_STREAM_LIMIT)
                        intent.setPackage(ApplicationHelper.instance.packageName)
                        ApplicationHelper.instance.sendBroadcast(intent)
                    }
                } else {
                    setCamera(enabled)
                }
            } catch (e: NotImplementedError) {
                L.e { "[Call] CallMediaController setCameraEnabled NotImplementedError, camera not supported on this device: ${e.message}" }
                roomCtl.updateCameraEnabled(false)
                showToast(ResUtils.getString(R.string.call_enable_camera_not_implemented_error))
            } catch (e: Throwable) {
                L.e { "[Call] CallMediaController setCameraEnabled error = ${e.stackTraceToString()}" }
                roomCtl.updateCameraEnabled(false)
            }
        }
    }

    private suspend fun setCamera(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            room.localParticipant.setCameraEnabled(enabled)
        }
        roomCtl.updateCameraEnabled(enabled)
    }

    /** Flips the local camera between front and back. No-op if camera is not publishing. */
    fun flipCamera() {
        val vt = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        val newPos = when (vt.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> null
        }
        vt.switchCamera(position = newPos)
    }
}
