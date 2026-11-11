package com.difft.android.call.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.manager.AudioRouteHost
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


internal enum class ObservedRoute { BLUETOOTH, SPEAKER, EARPIECE_OR_WIRED }

internal fun ObservedRoute.matches(kind: AudioDeviceKind): Boolean = when (this) {
    ObservedRoute.BLUETOOTH -> kind == AudioDeviceKind.BLUETOOTH_HEADSET
    ObservedRoute.SPEAKER -> kind == AudioDeviceKind.SPEAKERPHONE
    
    
    
    
    ObservedRoute.EARPIECE_OR_WIRED ->
        kind == AudioDeviceKind.EARPIECE || kind == AudioDeviceKind.WIRED_HEADSET
}

@RequiresApi(Build.VERSION_CODES.S)
private const val TYPE_BLE_HEADSET = AudioDeviceInfo.TYPE_BLE_HEADSET

private val BLUETOOTH_COMM_TYPES: Set<Int> =
    setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET)

private val WIRED_COMM_TYPES: Set<Int> = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)








internal fun commDeviceRoute(type: Int): ObservedRoute? = when {
    type in BLUETOOTH_COMM_TYPES -> ObservedRoute.BLUETOOTH
    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> ObservedRoute.SPEAKER
    type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> ObservedRoute.EARPIECE_OR_WIRED
    type in WIRED_COMM_TYPES -> ObservedRoute.EARPIECE_OR_WIRED
    else -> null
}













internal fun inferObservedRoute(commType: Int?, scoOn: Boolean, speakerOn: Boolean): ObservedRoute {
    val fromComm = commType?.let(::commDeviceRoute)
    return when {
        scoOn || fromComm == ObservedRoute.BLUETOOTH -> ObservedRoute.BLUETOOTH
        speakerOn || fromComm == ObservedRoute.SPEAKER -> ObservedRoute.SPEAKER
        else -> ObservedRoute.EARPIECE_OR_WIRED
    }
}





internal fun observedVia(
    route: ObservedRoute,
    scoOn: Boolean,
    speakerOn: Boolean,
    commType: Int?,
): String = when {
    route == ObservedRoute.BLUETOOTH && scoOn -> "sco"
    route == ObservedRoute.SPEAKER && speakerOn -> "speakerphoneOn"
    commType != null -> "commDevice"
    else -> "legacyNegative"
}













class AudioRouteApplier(
    appContext: Context,
    private val host: AudioRouteHost,
    private val audioHandler: AudioSwitchHandler,
    private val scope: CoroutineScope,
    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    @VisibleForTesting
    internal val wakeSignals = AudioRouteWakeSignals(appContext, host, audioManager)

    private var collectJob: Job? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        
        
        L.e { "[call] audioRoute applier collector died: ${e.stackTraceToString()}" }
    }

    
    fun start() {
        synchronized(this) {
            if (collectJob != null) {
                L.w { "[call] audioRoute applier start ignored: already started" }
                return
            }
            wakeSignals.start()
            collectJob = scope.launch(workDispatcher + exceptionHandler) {
                
                
                host.pendingRoute.collectLatest { applying -> runAttempt(applying.device) }
            }
        }
        L.i {
            "[call] audioRoute applier started intervalMs=$RETRY_INTERVAL_MS budgetMs=$BUDGET_MS " +
                wakeSignals.summary()
        }
    }

    




    fun stop() {
        synchronized(this) {
            collectJob?.cancel()
            collectJob = null
            wakeSignals.stop()
        }
        L.i { "[call] audioRoute applier stopped" }
    }

    private suspend fun runAttempt(target: AudioDevice) {
        L.i { "[call] audioRoute apply begin kind=${target.kind}" }
        
        
        
        
        awaitDevicesReady(target)
        val outcome = try {
            withTimeoutOrNull(BUDGET_MS) { driveUntilSettled(target) }
                ?: Outcome.Failed(
                    
                    
                    
                    
                    
                    
                    
                    if (isTargetAbsent(target)) AudioRouteFailure.DEVICE_GONE
                    else AudioRouteFailure.TIMEOUT
                )
        } catch (e: CancellationException) {
            
            
            throw e
        } catch (e: Exception) {
            L.e { "[call] audioRoute apply crashed kind=${target.kind}: ${e.stackTraceToString()}" }
            Outcome.Failed(AudioRouteFailure.ERROR)
        }
        when (outcome) {
            Outcome.Confirmed -> host.onRouteConfirmed(target)
            Outcome.Aborted ->
                L.i { "[call] audioRoute apply aborted kind=${target.kind} reason=ownershipLost" }
            is Outcome.Failed -> host.onRouteFailed(target, outcome.cause)
        }
    }

    private suspend fun awaitDevicesReady(target: AudioDevice) {
        if (host.routeSnapshot.value.availableDevices.isNotEmpty()) return
        L.i { "[call] audioRoute apply parked kind=${target.kind} reason=noDevices" }
        host.routeSnapshot.first { it.availableDevices.isNotEmpty() }
        L.i { "[call] audioRoute apply resumed kind=${target.kind}" }
    }

    private suspend fun driveUntilSettled(target: AudioDevice): Outcome {
        var round = 0
        
        var absentRounds = 0
        var signalWakes = 0
        while (true) {
            
            
            
            if (!ownsAttempt(target)) return Outcome.Aborted

            
            
            
            
            if (isTargetAbsent(target)) {
                absentRounds++
                if (absentRounds >= GONE_CONFIRM_ROUNDS) {
                    return Outcome.Failed(AudioRouteFailure.DEVICE_GONE)
                }
                L.i {
                    "[call] audioRoute apply targetAbsent kind=${target.kind} " +
                        "absentRounds=$absentRounds of=$GONE_CONFIRM_ROUNDS"
                }
                
                
                signalWakes = awaitNextRound(target, signalWakes)
                continue
            }
            absentRounds = 0

            
            
            val reading = observeRoute()
            if (reading.route.matches(target.kind)) {
                L.i {
                    "[call] audioRoute apply confirmed kind=${target.kind} round=$round " +
                        "via=${reading.via}"
                }
                return Outcome.Confirmed
            }
            round++
            val mode = ensureCommunicationMode()
            if (!forceReapply(target)) return Outcome.Aborted
            L.i {
                "[call] audioRoute apply round=$round kind=${target.kind} " +
                    "observed=${reading.route} scoOn=${reading.scoOn} " +
                    "spkOn=${reading.speakerOn} commType=${reading.commType} " +
                    "mode=$mode commDevices=${commDevicesSummary()}"
            }
            signalWakes = awaitNextRound(target, signalWakes)
        }
    }

    private fun ownsAttempt(target: AudioDevice): Boolean {
        val state = host.routeSnapshot.value.state
        return state is AudioRouteState.Applying && state.device.kind == target.kind
    }

    







    private fun isTargetAbsent(target: AudioDevice): Boolean {
        val devices = host.routeSnapshot.value.availableDevices
        return devices.isNotEmpty() && devices.none { it.kind == target.kind }
    }

    











    private fun forceReapply(target: AudioDevice): Boolean {
        if (!ownsAttempt(target)) {
            val now = (host.routeSnapshot.value.state as? AudioRouteState.Applying)?.device?.kind
            L.w { "[call] audioRoute reapply skipped kind=${target.kind} reason=staleAttempt now=$now" }
            return false
        }
        try {
            audioHandler.selectDevice(null)
            audioHandler.selectDevice(target)
        } catch (e: Exception) {
            L.w { "[call] audioRoute reapply failed kind=${target.kind}: ${e.message}" }
        }
        return true
    }

    





    private suspend fun awaitNextRound(target: AudioDevice, wakes: Int): Int {
        if (wakes >= MAX_SIGNAL_WAKES) {
            delay(RETRY_INTERVAL_MS)
            return wakes
        }
        val woke = withTimeoutOrNull(RETRY_INTERVAL_MS) { wakeSignals.await() } != null
        if (!woke) return wakes
        val next = wakes + 1
        if (next == MAX_SIGNAL_WAKES) {
            L.w { "[call] audioRoute applySignalCap kind=${target.kind} wakes=$next" }
        }
        return next
    }

    





    @Suppress("DEPRECATION")
    private fun observeRoute(): RouteReading {
        val scoOn = safeRead("isBluetoothScoOn") { audioManager.isBluetoothScoOn } ?: false
        val speakerOn = safeRead("isSpeakerphoneOn") { audioManager.isSpeakerphoneOn } ?: false
        val commType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeRead("communicationDevice") { audioManager.communicationDevice?.type }
        } else {
            null
        }
        return RouteReading(inferObservedRoute(commType, scoOn, speakerOn), scoOn, speakerOn, commType)
    }

    









    private fun ensureCommunicationMode(): Int? {
        val mode = safeRead("mode") { audioManager.mode } ?: return null
        if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) return mode
        L.w { "[call] audioRoute mode drifted actual=$mode correcting=MODE_IN_COMMUNICATION" }
        safeWrite("mode") { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        return mode
    }

    



    private fun commDevicesSummary(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeRead("availableCommunicationDevices") {
                audioManager.availableCommunicationDevices.joinToString(",") { "${it.type}:${it.id}" }
            } ?: "?"
        } else {
            "n/a"
        }

    private inline fun <T> safeRead(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            L.w { "[call] audioRoute read failed what=$what: ${e.message}" }
            null
        }

    private inline fun safeWrite(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            L.w { "[call] audioRoute write failed what=$what: ${e.message}" }
        }
    }

    private sealed interface Outcome {
        data object Confirmed : Outcome

        
        data object Aborted : Outcome
        data class Failed(val cause: AudioRouteFailure) : Outcome
    }

    private data class RouteReading(
        val route: ObservedRoute,
        val scoOn: Boolean,
        val speakerOn: Boolean,
        val commType: Int?,
    ) {
        val via: String get() = observedVia(route, scoOn, speakerOn, commType)
    }

    private companion object {
        





        const val RETRY_INTERVAL_MS = 500L
        const val BUDGET_MS = 5000L

        





        const val GONE_CONFIRM_ROUNDS = 3

        




        val MAX_SIGNAL_WAKES = (BUDGET_MS / RETRY_INTERVAL_MS).toInt()
    }
}
