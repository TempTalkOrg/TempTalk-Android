package com.difft.android.call.cleanup

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
import com.difft.android.call.session.CallTypeCoordinator
import com.difft.android.call.ui.screenshare.ScreenShareFloatingSpeakerStateHolder
import com.difft.android.call.ui.screenshare.ScreenSharePreWarmer
import io.livekit.android.audio.AudioSwitchHandler
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull










internal object CallCleanupSteps {

    @Suppress("LongParameterList")
    fun build(
        sessionId: Long,
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
            statisticsLogManager.releaseRoom(sessionId)
        },
        CallCleanupExecutor.Step("shouldTriggerFeedbackView") { shouldTriggerFeedbackView() },
        CallCleanupExecutor.Step("cancelCallTimeoutCheck") { timeoutMonitor.cancelIfActive() },
        CallCleanupExecutor.Step("cancelRoomEventDispatcher") { roomEventDispatcher?.cancelJobs() },
        
        
        
        
        CallCleanupExecutor.Step("resetNetworkQuality") {
            withContext(Dispatchers.Main.immediate) { networkQualityCoordinator?.stop() }
        },
        CallCleanupExecutor.Step("roomCtl.disconnectAndRelease") {
            withTimeoutOrNull(5000L) { roomCtl.disconnectAndRelease() }
                ?: L.w { "[Call] CallCleanupSteps: roomCtl.disconnectAndRelease timeout" }
        },
        CallCleanupExecutor.Step("stopAudioHandler") {
            audioProcessor.release()
            
            
            
            audioSetup.stop()
            audioHandler.stop()
        },
        CallCleanupExecutor.Step("stopTimers") {
            timerManager.stopCallTimer()
            timerManager.stopCountdown()
        },
        CallCleanupExecutor.Step("clearConnectedServerUrl") { LCallEngine.clearConnectedServerUrl(sessionId) },
        CallCleanupExecutor.Step("resetFeedbackBinder") { feedbackBinder.reset() },
        CallCleanupExecutor.Step("cancelSpeakerStateJobs") { speakerState.cancelJobs() },
        CallCleanupExecutor.Step("cancelScreenShareFloatingSpeaker") { screenShareFloatingSpeakerStateHolder?.cancel() },
        CallCleanupExecutor.Step("cancelScreenSharePreWarmer") { screenSharePreWarmer.cancelJobs() },
        CallCleanupExecutor.Step("cancelCallTypeCoordinator") { callTypeCoordinator?.cancelJobs() },
        CallCleanupExecutor.Step("clearE2eeKey") { clearE2eeKey() },
    )
}
