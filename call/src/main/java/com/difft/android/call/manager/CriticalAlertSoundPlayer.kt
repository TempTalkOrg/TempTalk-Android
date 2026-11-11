package com.difft.android.call.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.R
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton



@Singleton
class CriticalAlertSoundPlayer @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val criticalAlertStateManager: CriticalAlertStateManager,
    private val callVibrationManager: CallVibrationManager,
    private val criticalAlertCallGate: CriticalAlertCallGate,
) {
    
    
    private val soundLock = Any()

    
    @Volatile
    private var currentRingtone: Ringtone? = null

    
    @Volatile
    private var alertAutoStopJob: Job? = null

    
    @Volatile
    private var ringtoneLoopJob: Job? = null

    
    
    private var playTokenOwnerId: Int? = null

    
    private val vibrationPattern = longArrayOf(0, 1000, 1000)

    
    private val ringtoneLoopCheckInterval = 300L

    


    private val ringtoneExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "CriticalAlert-Ringtone").apply { isDaemon = true }
        }
    }

    private fun postRingtoneAction(action: () -> Unit) {
        try {
            ringtoneExecutor.execute(action)
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertSoundPlayer Failed to post ringtone action: ${e.message}" }
        }
    }

    private fun safeStopRingtone(ringtone: Ringtone?) {
        if (ringtone == null) return
        try {
            ringtone.stop()
        } catch (e: IllegalStateException) {
            L.e { "[Call] CriticalAlertSoundPlayer Ringtone is in an illegal state: ${e.message}" }
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertSoundPlayer Stop failed: ${e.message}" }
        }
    }

    companion object {
        
        private const val CRITICAL_ALERT_VIBRATION_DURATION_MS = 30_000L
    }

    

    private fun startRingtoneLoop(ringtone: Ringtone) {
        ringtoneLoopJob?.cancel()
        ringtoneLoopJob = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            
            postRingtoneAction {
                try {
                    ringtone.isLooping = true
                } catch (e: Exception) {
                    L.e { "[Call] CriticalAlertSoundPlayer set isLooping failed: ${e.message}" }
                }
                safePlayRingtone(ringtone)
            }
        } else {
            ringtoneLoopJob = appScope.launch(Dispatchers.IO) {
                
                while (isActive && currentRingtone === ringtone) {
                    
                    postRingtoneAction {
                        if (currentRingtone === ringtone) {
                            val playing = try {
                                ringtone.isPlaying
                            } catch (e: Exception) {
                                false
                            }
                            if (!playing) {
                                safePlayRingtone(ringtone)
                            }
                        }
                    }
                    delay(ringtoneLoopCheckInterval)
                }
            }
        }
    }

    private fun safePlayRingtone(ringtone: Ringtone) {
        try {
            ringtone.play()
        } catch (e: Exception) {
            L.e { "[Call] CriticalAlertSoundPlayer Play failed: ${e.message}" }
        }
    }

    
    private fun startVibration() {
        postRingtoneAction { callVibrationManager.startAlarmVibration(vibrationPattern, repeat = 0) }
    }

    
    private fun stopVibration() {
        postRingtoneAction { callVibrationManager.stopVibration(CallVibrationManager.VibrationSource.CRITICAL_ALERT) }
    }

    

    private fun scheduleAlertAutoStop(notificationId: Int) {
        alertAutoStopJob?.cancel()
        alertAutoStopJob = appScope.launch {
            delay(CRITICAL_ALERT_VIBRATION_DURATION_MS)
            L.i { "[Call] CriticalAlertSoundPlayer alert auto-stop after ${CRITICAL_ALERT_VIBRATION_DURATION_MS}ms, id=$notificationId" }
            stopSoundIfMatch(notificationId)
        }
    }

    




    fun playSound(conversationId: String, notificationId: Int, roomId: String? = null, onAdmitted: () -> Unit = {}) {
        if (criticalAlertCallGate.shouldSilence(conversationId, roomId)) {
            L.i { "[Call] CriticalAlertSoundPlayer skip play reason=silenced id=$notificationId roomId=$roomId" }
            return
        }
        val token = System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) {
            
            synchronized(soundLock) {
                
                
                
                if (criticalAlertCallGate.shouldSilence(conversationId, roomId)) {
                    L.i { "[Call] CriticalAlertSoundPlayer skip queued play reason=silenced id=$notificationId roomId=$roomId" }
                    return@launch
                }
                criticalAlertStateManager.setCurrentPlayToken(token)
                playTokenOwnerId = notificationId
                val old = currentRingtone
                currentRingtone = null
                if (old != null) {
                    
                    ringtoneLoopJob?.cancel()
                    ringtoneLoopJob = null
                    alertAutoStopJob?.cancel()
                    alertAutoStopJob = null
                    postRingtoneAction { safeStopRingtone(old) }
                    stopVibration()
                }
            }
            onAdmitted()

            try {
                val ringtoneUri =
                    "android.resource://${context.packageName}/${R.raw.critical_alert}".toUri()
                val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }

                
                synchronized(soundLock) {
                    if (token != criticalAlertStateManager.getCurrentPlayToken()) {
                        L.i { "[Call] CriticalAlertSoundPlayer Ignored old play call for id=$notificationId (token=$token)" }
                        if (ringtone != null) {
                            postRingtoneAction { safeStopRingtone(ringtone) }
                        }
                        return@synchronized
                    }

                    
                    
                    
                    if (criticalAlertCallGate.shouldSilence(conversationId, roomId)) {
                        L.i { "[Call] CriticalAlertSoundPlayer skip install reason=silenced id=$notificationId roomId=$roomId" }
                        criticalAlertStateManager.setCurrentPlayToken(0L)
                        playTokenOwnerId = null
                        
                        criticalAlertStateManager.resetSoundState()
                        if (ringtone != null) {
                            postRingtoneAction { safeStopRingtone(ringtone) }
                        }
                        return@synchronized
                    }

                    
                    if (ringtone == null) {
                        L.w { "[Call] CriticalAlertSoundPlayer getRingtone returned null for notification $notificationId, skip play" }
                        return@synchronized
                    }

                    currentRingtone = ringtone
                    criticalAlertStateManager.setCurrentNotificationId(notificationId)
                    criticalAlertStateManager.setConversationId(conversationId)
                    criticalAlertStateManager.setIsPlayingSound(true)
                    L.i { "[Call] CriticalAlertSoundPlayer Playing ringtone for notification $notificationId" }
                    startRingtoneLoop(ringtone)
                    startVibration()
                    scheduleAlertAutoStop(notificationId)
                }

            } catch (e: Exception) {
                L.e { "[Call] CriticalAlertSoundPlayer Play failed: ${e.message}" }
            }
        }
    }

    

    fun stopSound() {
        synchronized(soundLock) {
            criticalAlertStateManager.setCurrentPlayToken(0L)
            playTokenOwnerId = null
            alertAutoStopJob?.cancel()
            alertAutoStopJob = null
            ringtoneLoopJob?.cancel()
            ringtoneLoopJob = null
            val rt = currentRingtone
            currentRingtone = null
            criticalAlertStateManager.resetSoundState()
            if (rt != null) {
                postRingtoneAction { safeStopRingtone(rt) }
            }
            stopVibration()
        }
    }

    
    fun stopSoundIfMatch(notificationId: Int) {
        synchronized(soundLock) {
            if (notificationId == criticalAlertStateManager.getCurrentNotificationId()) {
                criticalAlertStateManager.setCurrentPlayToken(0L)
                playTokenOwnerId = null
                alertAutoStopJob?.cancel()
                alertAutoStopJob = null
                ringtoneLoopJob?.cancel()
                ringtoneLoopJob = null
                val rt = currentRingtone
                currentRingtone = null
                criticalAlertStateManager.resetSoundState()
                L.i { "[Call] CriticalAlertSoundPlayer Stopped ringtone for $notificationId" }
                if (rt != null) {
                    postRingtoneAction { safeStopRingtone(rt) }
                }
                stopVibration()
            }
        }
    }

    




    
    fun ownsPlayToken(notificationId: Int): Boolean = synchronized(soundLock) { playTokenOwnerId == notificationId }

    fun stopIfOwner(notificationId: Int): Boolean {
        synchronized(soundLock) {
            if (playTokenOwnerId != notificationId) return false
            playTokenOwnerId = null
            criticalAlertStateManager.setCurrentPlayToken(0L)
            alertAutoStopJob?.cancel()
            alertAutoStopJob = null
            ringtoneLoopJob?.cancel()
            ringtoneLoopJob = null
            val rt = currentRingtone
            currentRingtone = null
            criticalAlertStateManager.resetSoundState()
            L.i { "[Call] CriticalAlertSoundPlayer Stopped by owner id=$notificationId" }
            if (rt != null) {
                postRingtoneAction { safeStopRingtone(rt) }
            }
            stopVibration()
            return true
        }
    }
}
