package com.difft.android.call.manager

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.PromptReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


class SpeakerStateHolder(
    private val scope: CoroutineScope,
    private val roomCtl: CallRoomController,
    private val onGoingCallStateManager: OnGoingCallStateManager,
) {
    private val mutex = Mutex()

    
    @Volatile
    private var noSpeakJob: Job? = null
    private var isNoSpeakerChecking = false
    private var isOnePersonChecking = false

    @Volatile
    private var hasOtherActiveSpeaker = false

    






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

    


    fun cancelJobs() {
        noSpeakJob?.cancel()
        noSpeakJob = null
    }

    private fun isTimeoutCheckApplicable(): Boolean =
        onGoingCallStateManager.isInCalling() && !onGoingCallStateManager.isInCallEnding()
}
