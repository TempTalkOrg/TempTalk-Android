package com.difft.android.call.media

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.difft.android.call.manager.targetedRoute
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch



internal class AudioRouteLifecycleGuard(
    private val scope: CoroutineScope,
    private val audioDeviceManager: AudioDeviceManager,
    private val roomState: StateFlow<Room.State>,
) {
    private var lifecycleJob: Job? = null
    private var starvationJob: Job? = null
    private val _switchStarved = MutableStateFlow(false)

    @VisibleForTesting
    internal val switchStarved: StateFlow<Boolean> = _switchStarved.asStateFlow()

    










    private var lastObservedState: Room.State? = null

    







    @Volatile
    private var timeoutKind: AudioDeviceKind? = null

    @Volatile
    private var consecutiveTimeouts = 0

    @Volatile
    private var rescues = 0

    








    @Volatile
    private var lastCountedFailure: AudioRouteState.Failed? = null

    






    @Volatile
    private var lastConfirmationsSeen = 0

    





    private val rescueInFlight = MutableStateFlow(false)

    




    private val rescueStartLock = Any()
    private var rescueStartBlocked = false

    fun start() {
        if (lifecycleJob != null) {
            L.w { "[call] audioRoute guard start ignored: already started" }
            return
        }
        synchronized(rescueStartLock) { rescueStartBlocked = false }
        lifecycleJob = scope.launch {
            launch { collectSwitchGeneration() }
            launch { collectSwitchReady() }
            launch { collectStarvation() }
            launch { collectWedgeRescue() }
        }
    }

    fun stop() {
        
        
        
        
        synchronized(rescueStartLock) { rescueStartBlocked = true }
        
        
        
        lifecycleJob?.cancel()
        lifecycleJob = null
        starvationJob = null
        _switchStarved.value = false
        
        
        endTimeoutStreak()
        lastCountedFailure = null
        lastConfirmationsSeen = 0
        rescues = 0
        rescueInFlight.value = false
    }

    
    private suspend fun collectSwitchGeneration() {
        roomState.collect { state ->
            when (state) {
                Room.State.DISCONNECTED -> invalidate(state)
                
                
                
                Room.State.CONNECTING ->
                    if (lastObservedState != Room.State.DISCONNECTED) invalidate(state)
                
                
                Room.State.CONNECTED, Room.State.RECONNECTING -> Unit
            }
            lastObservedState = state
        }
    }

    private fun invalidate(state: Room.State) {
        
        
        endTimeoutStreak()
        
        
        
        
        audioDeviceManager.onAudioSwitchInvalidated(
            reason = "roomState=$state",
            reseed = state == Room.State.CONNECTING,
        )
        
        
        if (audioDeviceManager.routeSnapshot.value.availableDevices.isNotEmpty()) onSwitchReady()
    }

    








    private suspend fun collectSwitchReady() {
        audioDeviceManager.availableDevices
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .filter { it }
            .collect { onSwitchReady() }
    }

    private fun onSwitchReady() {
        val snapshot = audioDeviceManager.routeSnapshot.value
        val count = snapshot.availableDevices.size
        val want = snapshot.requested
        
        
        
        if (want == null) {
            L.i { "[call] audioRoute switchReady count=$count replay=none" }
            return
        }
        
        
        
        val current = snapshot.targetedRoute
        if (current?.kind == want.kind) {
            L.i { "[call] audioRoute switchReady count=$count replay=skip reason=alreadyTargeted" }
            return
        }
        L.i {
            "[call] audioRoute switchReady count=$count replay=apply kind=${want.kind} " +
                "libTarget=${current?.kind}"
        }
        
        
        
        
        audioDeviceManager.select(want)
    }

    






    private suspend fun collectStarvation() = coroutineScope {
        combine(roomState, audioDeviceManager.availableDevices, rescueInFlight) { state, devices, rescuing ->
            
            
            state != Room.State.DISCONNECTED && devices.isEmpty() && !rescuing
        }.distinctUntilChanged().collect { starved ->
            starvationJob?.cancel()
            if (!starved) {
                _switchStarved.value = false
                return@collect
            }
            starvationJob = launch {
                delay(SWITCH_STARVED_WARN_MS)
                _switchStarved.value = true
                L.w {
                    "[call] audioRoute switchStarved roomState=${roomState.value} " +
                        "waitedMs=$SWITCH_STARVED_WARN_MS reason=libraryReportedNoDevices"
                }
                
                
                
                
                
                
                repeat(STARVED_RESEED_ATTEMPTS) { attempt ->
                    audioDeviceManager.reseedFromLibrary(reason = "starved attempt=${attempt + 1}")
                    delay(STARVED_RESEED_INTERVAL_MS)
                }
                L.w {
                    "[call] audioRoute switchStarvedUnrecovered attempts=$STARVED_RESEED_ATTEMPTS " +
                        "roomState=${roomState.value}"
                }
            }
        }
    }

    













    private suspend fun collectWedgeRescue() {
        audioDeviceManager.routeSnapshot.collect { snapshot ->
            
            
            
            
            
            if (snapshot.confirmations != lastConfirmationsSeen) {
                lastConfirmationsSeen = snapshot.confirmations
                endTimeoutStreak()
            }
            when (val state = snapshot.state) {
                is AudioRouteState.Failed -> onFailureObserved(state)
                is AudioRouteState.Confirmed -> endTimeoutStreak()
                
                
                
                is AudioRouteState.Applying, AudioRouteState.Idle -> Unit
            }
        }
    }

    private suspend fun onFailureObserved(failure: AudioRouteState.Failed) {
        
        
        if (failure === lastCountedFailure) return
        lastCountedFailure = failure
        
        
        if (failure.cause != AudioRouteFailure.TIMEOUT) {
            endTimeoutStreak()
            return
        }
        if (failure.device.kind != timeoutKind) {
            timeoutKind = failure.device.kind
            consecutiveTimeouts = 1
            return
        }
        consecutiveTimeouts++
        if (consecutiveTimeouts >= WEDGE_TIMEOUT_STREAK) rescue(failure.device.kind)
    }

    






    private suspend fun rescue(kind: AudioDeviceKind) {
        
        
        endTimeoutStreak()
        val state = roomState.value
        if (state != Room.State.CONNECTED) {
            
            
            L.w { "[call] audioRoute wedgeRescue skipped kind=$kind roomState=$state reason=notConnected" }
            return
        }
        if (rescues >= MAX_WEDGE_RESCUES) {
            L.w { "[call] audioRoute wedgeRescueExhausted kind=$kind rescues=$rescues" }
            return
        }
        rescues++
        L.w {
            "[call] audioRoute wedgeRescue kind=$kind consecutiveTimeouts=$WEDGE_TIMEOUT_STREAK " +
                "rescues=$rescues/$MAX_WEDGE_RESCUES"
        }
        rescueInFlight.value = true
        try {
            
            
            
            audioDeviceManager.onAudioSwitchInvalidated(reason = "wedgeRescue", reseed = false)
            val handler = audioDeviceManager.audioHandler
            handler.stop()
            
            
            
            
            
            
            delay(SWITCH_REBUILD_SETTLE_MS)
            
            
            
            
            
            
            
            
            
            
            
            
            
            val settledState = roomState.first {
                it == Room.State.CONNECTED || it == Room.State.DISCONNECTED
            }
            if (settledState != Room.State.CONNECTED) {
                L.w {
                    "[call] audioRoute wedgeRescue aborted kind=$kind roomState=$settledState " +
                        "reason=teardownDuringSettle"
                }
                return
            }
            
            
            
            synchronized(rescueStartLock) {
                if (rescueStartBlocked) {
                    L.w { "[call] audioRoute wedgeRescue aborted kind=$kind reason=guardStopped" }
                    return
                }
                handler.start()
            }
        } finally {
            rescueInFlight.value = false
        }
    }

    



    private fun endTimeoutStreak() {
        timeoutKind = null
        consecutiveTimeouts = 0
    }

    private companion object {
        





        const val SWITCH_STARVED_WARN_MS = 3_000L

        





        const val STARVED_RESEED_ATTEMPTS = 3

        
        const val STARVED_RESEED_INTERVAL_MS = 1_000L

        





        const val WEDGE_TIMEOUT_STREAK = 2

        

        const val MAX_WEDGE_RESCUES = 2

        


        const val SWITCH_REBUILD_SETTLE_MS = 150L
    }
}
