package com.difft.android.call.session

import androidx.annotation.MainThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.cleanup.CallCleanupExecutor
import com.difft.android.call.cleanup.CallCleanupSteps
import com.difft.android.call.util.CallWaitDialogUtil












@MainThread
internal fun CallSessionGraph.runCallLevelTeardown() {
    teardownAction("cancelExitFallback") { endController.cancelExitFallback() }
    teardownAction("releaseProximity") { proximity.release() }
    teardownAction("stopVibration") { deps.callVibrationManager.stopVibration() }
    teardownAction("stopRingTone") { deps.callRingtoneManager.stopRingTone() }
    teardownAction("stopParticipantObservation") { deps.contactorCacheManager.stopParticipantObservation() }
    teardownAction("clearContactorCache") { deps.contactorCacheManager.clearContactorCache() }
    teardownAction("stopOngoingCallService") { deps.ongoingCallService.stop(sessionId, "session end") }
    teardownAction("endPresence") { publisher.endPresence() }
    teardownAction("updateCallingState") { roomId?.let { deps.callDataManager.updateCallingState(it, false) } }
    teardownAction("releaseEntryWaitDialog") { CallWaitDialogUtil.dismiss(params.entryWaitToken) }
}

private inline fun CallSessionGraph.teardownAction(name: String, action: () -> Unit) {
    runCatching(action).onFailure { L.e(it) { "[Call][Session] teardown $name failed id=$sessionId" } }
}


internal fun CallSessionGraph.buildCleanupSteps(): List<CallCleanupExecutor.Step> = CallCleanupSteps.build(
    sessionId = sessionId,
    audioProcessor = audio.processor,
    audioHandler = audio.handler,
    audioSetup = audio.setup,
    timerManager = timerManager,
    timeoutMonitor = timeoutMonitor,
    roomCtl = roomCtl,
    speakerState = speakerState,
    screenShareFloatingSpeakerStateHolder = screenShareSpeakerLazy.takeIf { it.isInitialized() }?.value,
    screenSharePreWarmer = screenSharePreWarmer,
    roomEventDispatcher = roomEventDispatcherLazy.takeIf { it.isInitialized() }?.value,
    callTypeCoordinator = callTypeCoordinatorLazy.takeIf { it.isInitialized() }?.value,
    networkQualityCoordinator = networkQualityLazy.takeIf { it.isInitialized() }?.value,
    statisticsLogManager = deps.statisticsLogManager,
    feedbackBinder = feedbackBinder,
    shouldTriggerFeedbackView = { shouldTriggerFeedbackView() },
    clearE2eeKey = ::clearE2eeKey,
)
