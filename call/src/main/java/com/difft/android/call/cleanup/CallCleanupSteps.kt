package com.difft.android.call.cleanup

import android.app.Application
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallEngine
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.feedback.CallFeedbackBinder
import com.difft.android.call.handler.CallTimeoutMonitor
import com.difft.android.call.handler.RoomEventDispatcher
import com.difft.android.call.manager.CallStatisticsLogManager
import com.difft.android.call.manager.SpeakerStateHolder
import com.difft.android.call.manager.TimerManager
import com.difft.android.call.media.CallAudioSetup
import com.difft.android.call.network.NetworkQualityCoordinator
import com.difft.android.call.service.ForegroundService
import com.difft.android.call.session.CallTypeCoordinator
import com.difft.android.call.ui.screenshare.ScreenShareFloatingSpeakerStateHolder
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import io.livekit.android.audio.AudioSwitchHandler
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Assembles the ordered list of cleanup [CallCleanupExecutor.Step]s used both
 * for user-initiated exit and `onCleared()` teardown.
 *
 * Kept stateless (a plain builder object) so the VM only pays the cost of one
 * call to produce the list rather than carrying ~30 lines of setup in the
 * class body.
 */
internal object CallCleanupSteps {

    @Suppress("LongParameterList")
    fun build(
        application: Application,
        audioProcessor: AudioPipelineProcessor,
        audioHandler: AudioSwitchHandler,
        audioSetup: CallAudioSetup,
        timerManager: TimerManager,
        timeoutMonitor: CallTimeoutMonitor,
        roomCtl: CallRoomController,
        speakerState: SpeakerStateHolder,
        screenShareFloatingSpeakerStateHolder: ScreenShareFloatingSpeakerStateHolder?,
        screenSharePreWarmer: ScreenSharePreWarmer,
        roomEventDispatcher: RoomEventDispatcher?,
        callTypeCoordinator: CallTypeCoordinator?,
        networkQualityCoordinator: NetworkQualityCoordinator?,
        statisticsLogManager: CallStatisticsLogManager,
        feedbackBinder: CallFeedbackBinder,
        shouldTriggerFeedbackView: () -> Unit,
        clearE2eeKey: () -> Unit,
    ): List<CallCleanupExecutor.Step> = listOf(
        CallCleanupExecutor.Step("flushStatisticsLogs") {
            statisticsLogManager.flushAll()
            statisticsLogManager.setRoomLocalId(null)
            statisticsLogManager.setRoomId(null)
        },
        CallCleanupExecutor.Step("shouldTriggerFeedbackView") { shouldTriggerFeedbackView() },
        CallCleanupExecutor.Step("cancelCallTimeoutCheck") { timeoutMonitor.cancelIfActive() },
        CallCleanupExecutor.Step("cancelRoomEventDispatcher") { roomEventDispatcher?.cancelJobs() },
        // Before disconnectAndRelease: stops the tick and the room-state collector so the room can't
        // be released while a tick is still reading the quality providers. The hop to main is
        // required, not cosmetic: steps run on Dispatchers.IO, while the coordinator drives a
        // lock-free state machine that every other entry point touches from the main dispatcher.
        CallCleanupExecutor.Step("resetNetworkQuality") {
            withContext(Dispatchers.Main.immediate) { networkQualityCoordinator?.stop() }
        },
        CallCleanupExecutor.Step("roomCtl.disconnectAndRelease") {
            withTimeoutOrNull(5000L) { roomCtl.disconnectAndRelease() }
                ?: L.w { "[Call] CallCleanupSteps: roomCtl.disconnectAndRelease timeout" }
        },
        CallCleanupExecutor.Step("stopAudioHandler") {
            audioProcessor.release()
            // audioSetup first: it cancels the route guard and the applier, both of which drive the
            // handler. Stopping the handler first left a window where the applier could still call
            // selectDevice on a dead switch, and where the guard's rebuild could restart one.
            audioSetup.stop()
            audioHandler.stop()
        },
        CallCleanupExecutor.Step("stopTimers") {
            timerManager.stopCallTimer()
            timerManager.stopCountdown()
        },
        CallCleanupExecutor.Step("stopService") {
            if (ForegroundService.isServiceRunning) {
                L.i { "[Call] CallCleanupSteps stop ongoing call service." }
                withContext(Dispatchers.Main) {
                    application.stopService(Intent(application, ForegroundService::class.java))
                }
            }
        },
        CallCleanupExecutor.Step("clearConnectedServerUrl") { LCallEngine.setConnectedServerUrl(null) },
        CallCleanupExecutor.Step("resetFeedbackBinder") { feedbackBinder.reset() },
        CallCleanupExecutor.Step("cancelSpeakerStateJobs") { speakerState.cancelJobs() },
        CallCleanupExecutor.Step("cancelScreenShareFloatingSpeaker") { screenShareFloatingSpeakerStateHolder?.cancel() },
        CallCleanupExecutor.Step("cancelScreenSharePreWarmer") { screenSharePreWarmer.cancelJobs() },
        CallCleanupExecutor.Step("cancelCallTypeCoordinator") { callTypeCoordinator?.cancelJobs() },
        CallCleanupExecutor.Step("clearE2eeKey") { clearE2eeKey() },
    )
}
