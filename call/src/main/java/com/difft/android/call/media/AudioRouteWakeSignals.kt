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

/**
 * Every platform wake-up source that can shorten [AudioRouteApplier]'s verification wait.
 *
 * Wake-up sources ONLY, never sources of truth: polling decides. A missing signal (OEMs that never
 * deliver the SCO broadcast, a route already connected before registration) must cost at most one
 * interval, and a comm-device callback reports the platform's routing target, not that audio flows.
 *
 * Takes its own lock, so it does not depend on the caller holding one.
 */
internal class AudioRouteWakeSignals(
    appContext: Context,
    private val host: AudioRouteHost,
    private val audioManager: AudioManager,
) {
    private val appContext: Context = appContext.applicationContext

    /** At most one pending wake-up; a stale one only skips one wait, never a verification. */
    private val signals = Channel<Unit>(Channel.CONFLATED)

    private var scoRegistered = false
    private var commRegistered = false

    /**
     * Written and read ONLY inside the API 31+ gated functions below, so the platform interface is
     * never resolved on a device that does not have it.
     */
    private var commWaker: AudioManager.OnCommunicationDeviceChangedListener? = null

    /**
     * Runs the listener body on the framework's dispatch thread: a hop would only add latency to a
     * wake-up whose purpose is to beat a 500 ms poll. Requires the body to stay non-blocking.
     */
    private val directExecutor = Executor { it.run() }

    /**
     * Only CONNECTED wakes the loop. A CONNECTED wake is followed by a confirming round that exits,
     * so wake-ups stay bounded; letting ERROR wake it would let a flapping headset drive hundreds
     * of reapply round trips inside one budget.
     */
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
                // A confirmed route dying mid-call has no contract expression yet; recorded so the
                // field logs can tell it apart from "never confirmed".
                L.w { "[call] audioRoute scoState=DISCONNECTED whileConfirmed=BLUETOOTH_HEADSET" }
            } else {
                L.i { "[call] audioRoute scoState=${scoStateName(state)} state=${snapshotState.logName}" }
            }
        }
    }

    /** Idempotent. */
    fun start() = synchronized(this) {
        registerScoReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerCommWaker()
    }

    /** Idempotent, callable from any thread. */
    fun stop() = synchronized(this) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) unregisterCommWaker()
        unregisterScoReceiver()
    }

    /** Suspends until any source signals. The caller owns the timeout / cadence policy. */
    suspend fun await() {
        signals.receive()
    }

    /** Positive control: a silent wake log must read as "not registered", never "logging broken". */
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
            // Verification degrades to polling only — still correct, at most one interval slower.
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
            // Adding the same instance twice throws and removing an unregistered one throws, so
            // this flag must be exact rather than optimistic: only set it on success.
            audioManager.addOnCommunicationDeviceChangedListener(directExecutor, waker)
            commRegistered = true
        } catch (e: Exception) {
            // Degrades to polling, exactly like an SCO registration failure.
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

    /**
     * Wakes the loop only for a callback reporting the route the CURRENT attempt is driving: our own
     * `selectDevice(null)` -> `selectDevice(target)` round trip makes the platform emit a clear and
     * a set callback every round, so an unfiltered wake would spend the whole budget echoing itself.
     *
     * Invariant: no blocking work, no `audioHandler` touch, no host mutation.
     */
    private fun onCommDeviceChanged(device: AudioDeviceInfo?) {
        // Our own clear hop; never information about the target.
        val route = device?.type?.let(::commDeviceRoute) ?: return
        val state = host.routeSnapshot.value.state
        val target = (state as? AudioRouteState.Applying)?.device
        if (target == null) {
            // No attempt outstanding: "another app took the route". Recorded, never acted on —
            // there is no contract expression for it, as with scoState=DISCONNECTED whileConfirmed.
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
