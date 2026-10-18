package com.difft.android.call.session

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.ProcessLifecycleOwner
import com.difft.android.base.utils.globalServices
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.ProximityScreenOffController
import com.difft.android.call.media.AudioRouteApplier
import com.difft.android.call.media.CallAudioSetup
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.track.video.CameraCapturerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import livekit.org.webrtc.CameraXHelper
import javax.inject.Inject

/**
 * Builds the session pieces that load native code or reach framework services, so a
 * [CallSession] can be constructed on the plain JVM in tests.
 */
interface CallSessionComponentsFactory {
    fun audioStack(params: CallSessionParams, scope: CoroutineScope, isDenoiseEnabled: () -> Boolean): CallAudioStack
    fun roomController(
        params: CallSessionParams,
        audioHandler: AudioSwitchHandler,
        audioProcessor: AudioPipelineProcessor,
    ): CallRoomController
    fun selfId(): String

    /** Registers the process-global camera provider; launches its work on IO inside [scope]. */
    fun registerCameraProvider(scope: CoroutineScope)

    /** The call's proximity screen-off lock, held in [scope] while [shouldHold] is true. */
    fun proximity(scope: CoroutineScope, shouldHold: Flow<Boolean>): ProximityScreenOffController
}

/** The audio collaborators of one call, built together in their original construction order. */
class CallAudioStack(
    val processor: AudioPipelineProcessor,
    val deviceManager: AudioDeviceManager,
    val routeApplier: AudioRouteApplier,
    val setup: CallAudioSetup,
) {
    val handler get() = deviceManager.audioHandler
}

internal class DefaultCallSessionComponentsFactory @Inject constructor(
    private val deps: CallSessionDeps,
) : CallSessionComponentsFactory {

    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null

    override fun audioStack(params: CallSessionParams, scope: CoroutineScope, isDenoiseEnabled: () -> Boolean): CallAudioStack {
        val application = deps.application
        val processor = AudioPipelineProcessor(application)
        val deviceManager = AudioDeviceManager(application, params.callIntent.callType, deps.userManager)
        // Drives and verifies every route target the manager publishes; holds no route state itself.
        val routeApplier = AudioRouteApplier(
            appContext = application,
            host = deviceManager,
            audioHandler = deviceManager.audioHandler,
            scope = scope,
        )
        val setup = CallAudioSetup(
            scope = scope,
            audioDeviceManager = deviceManager,
            audioProcessor = processor,
            callConfig = params.callConfig,
            isDenoiseEnabledProvider = isDenoiseEnabled,
            userManager = deps.userManager,
            routeApplier = routeApplier,
        )
        return CallAudioStack(processor, deviceManager, routeApplier, setup)
    }

    override fun roomController(
        params: CallSessionParams,
        audioHandler: AudioSwitchHandler,
        audioProcessor: AudioPipelineProcessor,
    ) = CallRoomController(
        appContext = deps.application,
        callIntent = params.callIntent,
        audioHandler = audioHandler,
        audioProcessor = audioProcessor,
        e2eeEnable = params.e2eeEnable,
        proxyConfigProvider = deps.proxyConfigProvider,
        decryptCallMKey = { eKey, eMKey -> deps.messageEncryptor.decryptCallKey(eKey, eMKey) }
    )

    override fun selfId(): String = globalServices.myId

    override fun proximity(scope: CoroutineScope, shouldHold: Flow<Boolean>) =
        ProximityScreenOffController(context = deps.application, scope = scope, shouldHold = shouldHold)

    @OptIn(ExperimentalCamera2Interop::class)
    override fun registerCameraProvider(scope: CoroutineScope) {
        val context: Context = deps.application
        scope.launch(Dispatchers.IO) {
            CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get()).let {
                if (it.isSupported(context)) {
                    CameraCapturerUtils.registerCameraProvider(it)
                    cameraProvider = it
                }
            }
        }
    }
}
