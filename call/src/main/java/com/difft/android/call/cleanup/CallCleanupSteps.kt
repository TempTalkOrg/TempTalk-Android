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
import com.difft.android.call.service.ForegroundService
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
        CallCleanupExecutor.Step("roomCtl.disconnectAndRelease") {
            withTimeoutOrNull(5000L) { roomCtl.disconnectAndRelease() }
                ?: L.w { "[Call] CallCleanupSteps: roomCtl.disconnectAndRelease timeout" }
        },
        CallCleanupExecutor.Step("stopAudioHandler") {
            audioProcessor.release()
            audioHandler.stop()
            audioSetup.stop()
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
        CallCleanupExecutor.Step("cancelRoomEventDispatcher") { roomEventDispatcher?.cancelJobs() },
        CallCleanupExecutor.Step("clearE2eeKey") { clearE2eeKey() },
    )
}
