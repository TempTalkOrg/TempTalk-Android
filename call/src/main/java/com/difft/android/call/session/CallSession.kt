package com.difft.android.call.session

import androidx.annotation.MainThread
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AppLockCallbackManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.CallIntent
import com.difft.android.call.R
import com.difft.android.call.cleanup.CallCleanupExecutor
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.handler.CallErrorHandler
import com.difft.android.call.handler.RtmMessageHandler
import com.difft.android.call.session.command.HangupReason
import com.difft.android.call.util.CallWaitDialogUtil
import com.github.TempTalkOrg.audio_pipeline.AudioModule
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext

/**
 * One call: owns the call's collaborators ([CallSessionGraph]), their coroutine scopes and the
 * end sequence. Created and started by [CallSessionHolder.start] from the call's entry point
 * ([CallSessionLauncher]); call pages attach to it. Construction has no external effect: the call
 * begins in [start].
 *
 * Holds no `ViewModel`, `Activity`, Activity `Context` or UI controller.
 */
@Suppress("TooManyFunctions") // read/command surface forwarded to the thin LCallViewModel
class CallSession @AssistedInject internal constructor(
    @Assisted val params: CallSessionParams,
    @Assisted private val onEnded: (CallSession) -> Unit,
    deps: CallSessionDeps,
    private val dispatchers: CallSessionDispatchers,
    components: CallSessionComponentsFactory,
) {
    /** Log correlation and the key of this session's OnGoingCallStateManager projection fence. */
    val id: Long = NEXT_ID.incrementAndGet()
    private val job = SupervisorJob()

    /** Base scope for session work that is not main-confined (Default). */
    internal val scope = CoroutineScope(job + dispatchers.default + CoroutineName("CallSession-$id"))

    /** Same [job] as [scope] on `Main.immediate`: the context `viewModelScope` gave every collaborator. */
    internal val mainScope = CoroutineScope(job + dispatchers.main + CoroutineName("CallSession-$id-main"))

    // endSessionWithoutUi: the end controller may finish off main (exit handler on appScope), while
    // end() is main-confined. Main.immediate runs it inline on main, posts it otherwise; a cancelled
    // Job means the session already ended, so the skipped launch is correct.
    internal val graph = CallSessionGraph(
        params, deps, components, mainScope, sessionId = id,
        endSessionWithoutUi = { mainScope.launch { end(CallSessionEndReason.NO_UI) } },
    )
    private val endCalled = AtomicBoolean(false)
    private val scopeCancelled = AtomicBoolean(false)

    // Main-confined: armed by start(), cancelled by end().
    private var firstAttachWatchdog: Job? = null

    // The session handles every room error, page or not, from start to end.
    private val errorHandler = CallErrorHandler(
        scope = mainScope,
        isStartAction = params.callIntent.action == CallIntent.Action.START_CALL,
        isLive = { !isEnded && !graph.endController.isEnding },
        onEndCall = { graph.endController.finish(CallFinishRequest.LOCAL) },
    )

    // Main-confined: launched by start(), cancelled by end().
    private var errorCollection: Job? = null

    // Registered by start(), removed by end(); held here because AppLockCallbackManager keeps only a
    // weak reference. An unlock clears the app-lock flag whether or not a call page is up.
    private val appUnlockListenerId = "CallSession_$id"
    private val appUnlockListener: (Boolean) -> Unit = { unlocked ->
        if (unlocked) graph.deps.onGoingCallStateManager.setNeedAppLock(false)
    }

    private val _roomState = MutableStateFlow<CallRoomState>(CallRoomState.Pending)

    /** The room setup's outcome; written once, on the session's Default scope. */
    val roomState: StateFlow<CallRoomState> = _roomState.asStateFlow()

    /**
     * Begins the call: projection ownership, Phase A, the app-unlock listener, the ongoing-call
     * service, the room setup (Phase B) and the first-attach watchdog. A start that throws ends the session
     * ([CallSessionEndReason.START_FAILED]), which undoes whatever part of it ran, and returns false.
     */
    @MainThread
    internal fun start(): Boolean = try {
        // First: the newest session owns the OnGoingCallStateManager projection before any write.
        graph.publisher.bind()
        graph.startPhaseA(ioLaunchScope = scope)
        // After Phase A seeded the app-lock flag.
        AppLockCallbackManager.addListener(appUnlockListenerId, appUnlockListener)
        seedOneOnOneInvitee()
        // Keyed to this session: only this session's end stops it (in the foreground, or deferred).
        graph.deps.ongoingCallService.start(id)
        // Single consumer of the room's errors.
        errorCollection = mainScope.launch { graph.roomCtl.error.collect(errorHandler::handleError) }
        launchRoomSetup()
        armFirstAttachWatchdog()
        true
    } catch (e: Exception) {
        L.e(e) { "[Call][Session] start failed id=$id" }
        end(CallSessionEndReason.START_FAILED)
        false
    }

    /**
     * Phase B, once, on the session's Default scope. Any failure, an abort for a release in flight
     * included, resolves [roomState] to [CallRoomState.Failed] and ends the session; a session that
     * ended first, or a cancellation, resolves nothing.
     */
    private fun launchRoomSetup() {
        scope.launch {
            // The Job outlives end() until the last cleanup step: an ended call builds no room.
            if (isEnded) return@launch
            val room = try {
                graph.startRoomDependentWiring()
                graph.roomCtl.roomOrNull()?.takeUnless { graph.roomCtl.isReleaseIntended() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                L.w(e) { "[Call][Session] room setup aborted id=$id" }
                null
            }
            _roomState.value = room?.let(CallRoomState::Ready) ?: CallRoomState.Failed
            mainScope.launch(Dispatchers.Main) { onRoomSetupResolved(ready = room != null) }
        }
    }

    @MainThread
    private fun onRoomSetupResolved(ready: Boolean) {
        L.i { "[Call][Session] room setup resolved id=$id ready=$ready pages=${graph.endController.uiAttachCount}" }
        // No page is waiting on the room: nothing else closes the entry point's wait dialog.
        if (graph.endController.uiAttachCount == 0) releaseEntryWaitDialog()
        if (!ready) end(CallSessionEndReason.ROOM_FAILED)
    }

    /**
     * Releases the entry point's share of the wait dialog. Idempotent: a released claim is dead, and
     * releasing a dead claim closes nothing.
     */
    @MainThread
    fun releaseEntryWaitDialog() = CallWaitDialogUtil.dismiss(params.entryWaitToken)

    /** A 1v1 start invites its peer: the call shows the peer as awaiting from the first frame. */
    private fun seedOneOnOneInvitee() {
        val intent = params.callIntent
        val oneOnOneStart = intent.action == CallIntent.Action.START_CALL && intent.callType == CallType.ONE_ON_ONE.type
        if (oneOnOneStart && params.callRole != CallRole.CALLEE) {
            intent.conversationId?.let { graph.participantManager.addAwaitingJoinInvitees(listOf(it)) }
        }
    }

    /**
     * A session whose page never attaches (the launch was blocked, or the page finished before its
     * view model existed) leaves the call after [FIRST_ATTACH_TIMEOUT_MS] rather than holding the
     * service and refusing every later call.
     */
    private fun armFirstAttachWatchdog() {
        firstAttachWatchdog = mainScope.launch {
            delay(FIRST_ATTACH_TIMEOUT_MS)
            val endController = graph.endController
            if (isEnded || endController.isEnding || endController.everAttached) return@launch
            L.w { "[Call][Session] no page attached in ${FIRST_ATTACH_TIMEOUT_MS / 1000} s id=$id, leaving" }
            endController.leaveWithoutPage()
        }
    }

    /** Whether [end] has run; main-confined reads. */
    internal val isEnded: Boolean get() = endCalled.get()

    /**
     * Ends the session; only the first call does anything. In order: the call is marked ending,
     * the call-level teardown runs ([runCallLevelTeardown]), the cleanup executor starts with the
     * scope cancel as its last step, and last of all [CallSessionHolder.current] is cleared — in a
     * `finally`, so a throwing action can never leave an ended session current. Nothing may follow
     * the clear: a waiter on the holder can resume inline and start the next call.
     */
    @MainThread
    fun end(reason: CallSessionEndReason) {
        if (isOffMain()) L.w { "[Call][Session] end off main thread id=$id reason=${reason.logTag}" }
        if (!endCalled.compareAndSet(false, true)) return
        L.i { "[Call][Session] end id=$id reason=${reason.logTag}" }
        try {
            endStep("markEnding") { graph.endController.markEnding() }
            endStep("cancelFirstAttachWatchdog") { firstAttachWatchdog?.cancel() }
            // An error raised by the teardown itself (the room disconnecting) is not reported.
            endStep("stopErrorHandling") {
                errorCollection?.cancel()
                errorHandler.cancelPending()
            }
            // Before the teardown restores the app-lock flag: a later unlock must not clear it.
            endStep("removeAppUnlockListener") { AppLockCallbackManager.removeListener(appUnlockListenerId) }
            graph.runCallLevelTeardown()
            endStep("startCleanup") {
                graph.cleanupExecutor.start(reason = reason.logTag, steps = graph.buildCleanupSteps() + cancelScopeStep())
            } ?: cancelScope()
        } finally {
            onEnded(this)
        }
    }

    /**
     * Last cleanup step: the session's coroutines stop only after every step before it ran, which
     * keeps the signaling those steps wait on alive.
     */
    private fun cancelScopeStep() = CallCleanupExecutor.Step("cancelSessionScope") {
        withContext(dispatchers.main) { cancelScope() }
    }

    private fun cancelScope() {
        if (!scopeCancelled.compareAndSet(false, true)) return
        job.cancel()
        endStep("screenSharePreWarmer.cleanupAll") { graph.screenSharePreWarmer.cleanupAll() }
    }

    private inline fun <T> endStep(name: String, action: () -> T): T? =
        runCatching(action).onFailure { L.e(it) { "[Call][Session] end step $name failed id=$id" } }.getOrNull()

    private fun isOffMain(): Boolean =
        (dispatchers.main as? MainCoroutineDispatcher)?.immediate?.isDispatchNeeded(EmptyCoroutineContext) == true

    /** Session→UI output; the VM binds its UI controller to it. */
    val uiState: CallSessionUiSource get() = graph.uiState

    // --- out-of-page commands (reached through CallSessionCommands) ---
    @MainThread fun hangup(reason: HangupReason) = graph.endController.hangup(reason)
    @MainThread fun onRemoteControl(type: CallActionType, roomId: String) =
        graph.endController.onRemoteControl(type, roomId)

    // --- in-page end commands and the finish effect ---
    fun requestExit(params: CallExitParams, endType: CallEndType) = graph.endController.requestExit(params, endType)
    fun markEnding(): Boolean = graph.endController.markEnding()
    val finishRequest: StateFlow<CallFinishRequest?> get() = graph.endController.finishRequest
    /** A call page's view model attached to this session; a configuration change keeps it attached. */
    @MainThread fun attachUi() = graph.endController.attachUi()

    /**
     * A call page's view model went away; the call goes on. When it was the last one, what was left
     * to that page is done here: the entry point's wait-dialog claim, released once the room
     * resolved, and a finish request no page rendered, which ends the session as a finish with no
     * page attached would.
     */
    @MainThread
    fun detachUi() {
        if (graph.endController.detachUi() > 0 || isEnded) return
        if (roomState.value != CallRoomState.Pending) releaseEntryWaitDialog()
        graph.endController.endPendingFinishWithoutPage()
    }

    // --- read surface ---
    val rtm: RtmMessageHandler get() = graph.rtm
    val participantManager get() = graph.participantManager
    val timerManager get() = graph.timerManager
    val audioDeviceManager get() = graph.audio.deviceManager
    val room: Room get() = graph.room
    val callStatus get() = graph.roomCtl.callStatus
    val callType get() = graph.roomCtl.callType
    val isNoSpeakSoloTimeout get() = graph.roomCtl.isNoSpeakSoloTimeout
    val micEnabled get() = graph.roomCtl.micEnabled
    val cameraEnabled get() = graph.roomCtl.cameraEnabled
    val deNoiseEnable get() = graph.mediaCtl.deNoiseEnable
    val deNoiseMode get() = graph.mediaCtl.deNoiseMode
    val voicePreset get() = graph.mediaCtl.voicePreset
    val mediaSendIssue get() = graph.mediaSendIssue
    val screenShareFloatingSpeaker get() = graph.screenShareFloatingSpeakerStateHolder

    fun getE2eeKey(): ByteArray? = graph.e2eeKey
    fun getRoomId(): String? = graph.roomId

    /** 1v1 remote peer id. Mirrors SingleParticipantCallPage.kt's exact fallback shape
     * (remote participant's LiveKit identity once joined, else conversationId before join). */
    fun getOneOnOnePeerId(): String? =
        graph.participantManager.participants.value.filterIsInstance<RemoteParticipant>().firstOrNull()?.identity?.value
            ?: params.callIntent.conversationId

    fun getCallRoomName(): String {
        val name = graph.callTypeCoordinator.currentRoomName
        // Read the participants StateFlow (already includes the local participant, so its
        // size equals room.remoteParticipants.size + 1) instead of touching the fail-loud
        // room getter. This Composable is invoked imperatively during recomposition and can
        // race with call teardown (releaseLocked sets released=true before callStatus flips
        // to DISCONNECTED); reading room there crashes with "room accessed after release".
        val participantNum = graph.participantManager.participants.value.size.coerceAtLeast(1)
        return if (graph.getCurrentCallType() == CallType.ONE_ON_ONE.type) name else "$name ($participantNum)"
    }

    fun getCurrentCallUidList(): List<String> = graph.currentCallUidList()

    // Uses the non-throwing roomStateOrNull() rather than the fail-loud room getter: this runs from a
    // touch handler that can legitimately race with call teardown (cleanup/onLeave releasing the room).
    // A released/not-yet-created room reads null here → button treated as not clickable, instead of crashing.
    fun isControlButtonClickEnabled(): Boolean = if (callType.value == CallType.ONE_ON_ONE.type)
        graph.roomCtl.roomStateOrNull() == Room.State.CONNECTED
    else
        callStatus.value == CallStatus.CONNECTED || callStatus.value == CallStatus.RECONNECTED

    // --- media commands ---
    fun setDeNoiseEnabled(enabled: Boolean) = graph.mediaCtl.setDeNoiseEnabled(enabled)
    fun setDeNoiseMode(mode: AudioModule) = graph.mediaCtl.setDeNoiseMode(mode)
    fun setVoicePreset(preset: VoicePreset) = graph.mediaCtl.setVoicePreset(preset)
    fun setMicEnabled(enabled: Boolean, publishMuted: Boolean = false, isShowBarrage: Boolean = true) =
        graph.setMicEnabled(enabled, publishMuted, isShowBarrage)
    fun setCameraEnabled(enabled: Boolean) = graph.setCameraEnabled(enabled)
    fun flipCamera() = graph.mediaCtl.flipCamera()

    /**
     * Mute-other over RTM. Success is reported only through the target's mic state; a failed
     * publish must not be silent, so it surfaces a toast naming [displayName].
     */
    fun toggleMute(participant: Participant, displayName: String) = graph.rtm.toggleMute(participant) { success ->
        L.i { "[Call] mute other target=${participant.identity?.value} success=$success" }
        if (!success) graph.showToast(ResUtils.getString(R.string.call_mute_participant_failed, displayName))
    }

    // --- session state commands ---
    fun showBarrage(participant: Participant, message: String, type: Int? = RTM_MESSAGE_TYPE_DEFAULT) =
        graph.showBarrage(participant, message, type)
    fun showToast(message: String) = graph.showToast(message)
    fun resetNoBodySpeakCheck() = graph.resetNoBodySpeakCheck()
    fun handleConnectedState() = graph.handleConnectedState()
    fun startCallDurationTimer() = graph.startCallDurationTimer()

    /** See `CallTypeCoordinator.applyInviteUpgrade`. */
    fun applyInviteUpgrade() = graph.callTypeCoordinator.applyInviteUpgrade()
    fun stopRingToneAndTimeoutCheck() = graph.stopRingToneAndTimeoutCheck()
    fun updateScreenShareFallback(participant: Participant) = graph.updateScreenShareFallback(participant)
    fun shouldTriggerFeedbackView() = graph.shouldTriggerFeedbackView()
    fun hasOtherActiveSpeaker(): Boolean = graph.speakerState.hasOtherActiveSpeaker()
    fun addAwaitingJoinInvitees(inviteeIds: List<String>) = graph.participantManager.addAwaitingJoinInvitees(inviteeIds)
    suspend fun handleCriticalAlertNew(gid: String? = null): Boolean = graph.criticalAlertDispatcher.send(gid)

    @AssistedFactory
    interface Factory {
        fun create(params: CallSessionParams, onEnded: (CallSession) -> Unit): CallSession
    }

    private companion object {
        val NEXT_ID = AtomicLong(0)
        const val FIRST_ATTACH_TIMEOUT_MS = 10_000L
    }
}
