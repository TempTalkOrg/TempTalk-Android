package com.difft.android.call.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioRouteHost
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.difft.android.call.manager.logName
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.Channel

private fun scoStateName(state: Int): String = when (state) {
    AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED"
    AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING"
    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED"
    else -> "ERROR($state)"
}










internal class AudioRouteWakeSignals(
    appContext: Context,
    private val host: AudioRouteHost,
    private val audioManager: AudioManager,
) {
    private val appContext: Context = appContext.applicationContext

    
    private val signals = Channel<Unit>(Channel.CONFLATED)

    private var scoRegistered = false
    private var commRegistered = false

    



    private var commWaker: AudioManager.OnCommunicationDeviceChangedListener? = null

    



    private val directExecutor = Executor { it.run() }

    




    @VisibleForTesting
    internal val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR,
            ) ?: return
            val snapshotState = host.routeSnapshot.value.state
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                L.i { "[call] audioRoute scoState=CONNECTED state=${snapshotState.logName}" }
                signals.trySend(Unit)
                return
            }
            val confirmedBt = (snapshotState as? AudioRouteState.Confirmed)
                ?.device?.kind == AudioDeviceKind.BLUETOOTH_HEADSET
            if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && confirmedBt) {
                
                
                L.w { "[call] audioRoute scoState=DISCONNECTED whileConfirmed=BLUETOOTH_HEADSET" }
            } else {
                L.i { "[call] audioRoute scoState=${scoStateName(state)} state=${snapshotState.logName}" }
            }
        }
    }

    
    fun start() = synchronized(this) {
        registerScoReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerCommWaker()
    }

    
    fun stop() = synchronized(this) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) unregisterCommWaker()
        unregisterScoReceiver()
    }

    
    suspend fun await() {
        signals.receive()
    }

    
    fun summary(): String = synchronized(this) {
        val comm = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "n/a"
            commRegistered -> "on"
            else -> "failed"
        }
        "wakeSco=$scoRegistered wakeComm=$comm"
    }

    private fun registerScoReceiver() {
        if (scoRegistered) return
        try {
            ContextCompat.registerReceiver(
                appContext,
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            scoRegistered = true
        } catch (e: Exception) {
            
            L.w { "[call] audioRoute sco receiver register failed: ${e.message}" }
        }
    }

    private fun unregisterScoReceiver() {
        if (!scoRegistered) return
        scoRegistered = false
        try {
            appContext.unregisterReceiver(scoReceiver)
        } catch (e: Exception) {
            L.w { "[call] audioRoute sco receiver unregister failed: ${e.message}" }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCommWaker() {
        if (commRegistered) return
        try {
            val waker = commWaker
                ?: AudioManager.OnCommunicationDeviceChangedListener { onCommDeviceChanged(it) }
                    .also { commWaker = it }
            
            
            audioManager.addOnCommunicationDeviceChangedListener(directExecutor, waker)
            commRegistered = true
        } catch (e: Exception) {
            
            L.w { "[call] audioRoute commWakerRegisterFailed error=${e.message}" }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterCommWaker() {
        if (!commRegistered) return
        commRegistered = false
        val waker = commWaker ?: return
        try {
            audioManager.removeOnCommunicationDeviceChangedListener(waker)
        } catch (e: Exception) {
            L.w { "[call] audioRoute commWakerUnregisterFailed error=${e.message}" }
        }
    }

    






    private fun onCommDeviceChanged(device: AudioDeviceInfo?) {
        
        val route = device?.type?.let(::commDeviceRoute) ?: return
        val state = host.routeSnapshot.value.state
        val target = (state as? AudioRouteState.Applying)?.device
        if (target == null) {
            
            
            val confirmedKind = (state as? AudioRouteState.Confirmed)?.device?.kind
            if (confirmedKind != null && !route.matches(confirmedKind)) {
                L.w { "[call] audioRoute commDeviceOther type=${device.type} whileConfirmed=$confirmedKind" }
            }
            return
        }
        if (!route.matches(target.kind)) return
        L.i { "[call] audioRoute commDeviceWake type=${device.type} target=${target.kind}" }
        signals.trySend(Unit)
    }
}
