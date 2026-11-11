package com.difft.android.call.manager

import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TimerManager(private val scope: CoroutineScope) {
    private var callTimerJob: Job? = null
    private var countdownJob: Job? = null

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()
    private val _callDurationText = MutableStateFlow("00:00")
    val callDurationText: StateFlow<String> = _callDurationText.asStateFlow()

    




    private val _callTimerRunning = MutableStateFlow(false)
    val callTimerRunning: StateFlow<Boolean> = _callTimerRunning.asStateFlow()

    private val _countDownEnabled = MutableStateFlow(false)
    val countDownEnabled: StateFlow<Boolean> = _countDownEnabled.asStateFlow()

    
    private val _countDownSeconds = MutableStateFlow(0L)
    val countDownSeconds: StateFlow<Long> = _countDownSeconds.asStateFlow()

    




    @Volatile
    private var callConnectedAtMillis = 0L

    





    fun startCallTimer(connectedAtMillis: Long, onTick: (String) -> Unit) {
        if (callTimerJob != null) return
        callConnectedAtMillis = connectedAtMillis
        _callTimerRunning.value = true
        callTimerJob = scope.launch {
            while (true) {
                delayToNextSecondBoundary()
                
                
                
                
                
                val sec = elapsedSeconds()
                _callDurationSeconds.value = sec
                val text = formatDuration(sec)
                _callDurationText.value = text
                onTick(text)
            }
        }
    }

    


    fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
        callConnectedAtMillis = 0L
        _callTimerRunning.value = false
    }

    





    private suspend fun delayToNextSecondBoundary() {
        val connectedAt = callConnectedAtMillis
        if (connectedAt <= 0L) {
            delay(1000)
            return
        }
        val sinceConnect = System.currentTimeMillis() - connectedAt
        delay((1000 - sinceConnect.mod(1000L)).coerceIn(1L, 1000L))
    }

    



    private fun elapsedSeconds(): Long {
        val connectedAt = callConnectedAtMillis
        if (connectedAt <= 0L) return 0L
        return ((System.currentTimeMillis() - connectedAt) / 1000).coerceAtLeast(0L)
    }

    


    fun startCountdown(initialSeconds: Long, onEnded: () -> Unit, onTick: (String) -> Unit) {
        countdownJob?.cancel()
        _countDownEnabled.value = true
        _countDownSeconds.value = initialSeconds
        onTick(formatDuration(initialSeconds))
        countdownJob = scope.launch {
            var currentSeconds = _countDownSeconds.value
            while (currentSeconds > 0) {
                delay(1000)
                currentSeconds -= 1
                _countDownSeconds.value = currentSeconds
                onTick(formatDuration(currentSeconds))
            }
            _countDownSeconds.value = 0
            onEnded()
        }
    }

    


    fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countDownEnabled.value = false
        _countDownSeconds.value = 0
    }

    


    @SuppressLint("DefaultLocale")
    private fun formatDuration(diffInSeconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(diffInSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(diffInSeconds) % 60
        val seconds = diffInSeconds % 60
        return if (hours <= 0) String.format("%02d:%02d", minutes, seconds)
        else String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    




    fun getCurrentDuration(): Long {
        return if (callTimerJob != null) elapsedSeconds() else _callDurationSeconds.value
    }
}