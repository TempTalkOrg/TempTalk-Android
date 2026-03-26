package com.difft.android.call.manager

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.PromptReminder
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thread-safe holder for all speaker-related mutable state.
 *
 * Previously these fields were bare `var`s on LCallViewModel, read/written
 * from multiple coroutine dispatchers (Main via combine-collect,
 * Default via onActiveSpeakersChanged debounce, IO via timeout jobs).
 * Wrapping them with a [Mutex] eliminates data races.
 */
class SpeakerStateHolder(
    private val scope: CoroutineScope,
    private val roomCtl: CallRoomController,
    private val participantManager: ParticipantManager,
    private val onGoingCallStateManager: OnGoingCallStateManager,
) {
    private val mutex = Mutex()

    // --- no-speaking / solo-member timeout detection ---
    @Volatile
    private var noSpeakJob: Job? = null
    private var isNoSpeakerChecking = false
    private var isOnePersonChecking = false

    // --- active speaker switch (debounce) ---
    private var lastSpeakers: List<Participant>? = null
    private var hasSpeaker: Boolean = false
    @Volatile
    private var hasOtherActiveSpeaker = false
    @Volatile
    private var debounceSpeakerUpdateJob: Job? = null

    /**
     * Called from [RoomEvent.ActiveSpeakersChanged].
     * Debounces speaker updates to avoid thrashing the participant sort.
     */
    fun onActiveSpeakersChanged(speakers: List<Participant>) {
        scope.launch {
            mutex.withLock {
                val filtered = speakers.filter { sp ->
                    participantManager.participants.value.contains(sp)
                }
                if (lastSpeakers == filtered || (hasSpeaker && filtered.isEmpty())) return@withLock

                lastSpeakers = filtered

                val delayTime = if (filtered.isEmpty()) {
                    hasSpeaker = false
                    2500L
                } else {
                    hasSpeaker = true
                    200L
                }

                debounceSpeakerUpdateJob?.cancel()
                debounceSpeakerUpdateJob = scope.launch(Dispatchers.Default) {
                    delay(delayTime)
                    withContext(Dispatchers.IO) {
                        participantManager.onActiveSpeakersChanged(filtered)
                        participantManager.sortParticipantsBySpeaker(filtered)
                        mutex.withLock { hasSpeaker = false }
                    }
                }
            }
        }
    }

    /**
     * Evaluates whether a silence / solo-member timeout should be started,
     * cancelled, or left alone. Called whenever participants or activeSpeakers change.
     *
     * @param hasRemoteParticipants whether any remote participants are in the room
     * @param isSilent true when nobody has a mic enabled or the speaker list is empty
     */
    fun checkNoSpeakOrOnePersonTimeout(
        hasRemoteParticipants: Boolean,
        isSilent: Boolean,
        callConfig: CallConfig,
    ) {
        scope.launch {
            mutex.withLock {
                if (hasRemoteParticipants) {
                    if (isSilent) {
                        hasOtherActiveSpeaker = false
                        if (isOnePersonChecking) {
                            noSpeakJob?.cancel()
                            isOnePersonChecking = false
                            roomCtl.updateNoSpeakSoloTimeout(false)
                        }
                        if (!isNoSpeakerChecking && isTimeoutCheckApplicable()) {
                            isNoSpeakerChecking = true
                            isOnePersonChecking = false
                            noSpeakJob?.cancel()
                            noSpeakJob = scope.launch(Dispatchers.IO) {
                                delay(
                                    callConfig.autoLeave?.promptReminder?.silenceTimeout
                                        ?: PromptReminder().silenceTimeout
                                )
                                withContext(Dispatchers.Main) {
                                    roomCtl.updateNoSpeakSoloTimeout(true)
                                }
                            }
                        }
                    } else {
                        hasOtherActiveSpeaker = true
                        noSpeakJob?.cancel()
                        isNoSpeakerChecking = false
                        isOnePersonChecking = false
                        roomCtl.updateNoSpeakSoloTimeout(false)
                    }
                } else {
                    if (!isOnePersonChecking && isTimeoutCheckApplicable()) {
                        isOnePersonChecking = true
                        isNoSpeakerChecking = false
                        noSpeakJob?.cancel()
                        noSpeakJob = scope.launch(Dispatchers.IO) {
                            delay(
                                callConfig.autoLeave?.promptReminder?.soloMemberTimeout
                                    ?: PromptReminder().soloMemberTimeout
                            )
                            withContext(Dispatchers.Main) {
                                roomCtl.updateNoSpeakSoloTimeout(true)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Resets the timeout detection state and re-evaluates from scratch.
     */
    fun reset(
        hasRemoteParticipants: Boolean,
        isSilent: Boolean,
        callConfig: CallConfig,
    ) {
        scope.launch {
            mutex.withLock {
                noSpeakJob?.cancel()
                isNoSpeakerChecking = false
                isOnePersonChecking = false
            }
            roomCtl.updateNoSpeakSoloTimeout(false)
            checkNoSpeakOrOnePersonTimeout(hasRemoteParticipants, isSilent, callConfig)
        }
    }

    fun hasOtherActiveSpeaker(): Boolean {
        L.i { "[Call] hasOtherActiveSpeaker: $hasOtherActiveSpeaker" }
        return hasOtherActiveSpeaker
    }

    /**
     * Cancels all internal jobs. Called during call cleanup.
     */
    fun cancelJobs() {
        noSpeakJob?.cancel()
        noSpeakJob = null
        debounceSpeakerUpdateJob?.cancel()
        debounceSpeakerUpdateJob = null
    }

    private fun isTimeoutCheckApplicable(): Boolean =
        onGoingCallStateManager.isInCalling() && !onGoingCallStateManager.isInCallEnding()
}
