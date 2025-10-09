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

    private val _countDownEnabled = MutableStateFlow(false)
    val countDownEnabled: StateFlow<Boolean> = _countDownEnabled.asStateFlow()

    // speech countdown (seconds)
    private val _countDownSeconds = MutableStateFlow(0L)
    val countDownSeconds: StateFlow<Long> = _countDownSeconds.asStateFlow()

    /**
     * Starts a call duration timer that tracks and updates the elapsed call time.
     */
    fun startCallTimer(onTick: (String) -> Unit) {
        if (callTimerJob != null) return
        callTimerJob = scope.launch {
            while (true) {
                delay(1000)
                val sec = (_callDurationSeconds.value ?: 0) + 1
                _callDurationSeconds.value = sec
                val text = formatDuration(sec)
                _callDurationText.value = text
                onTick(text)
            }
        }
    }

    /**
     * Stops the currently running call duration timer.
     */
    fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
    }

    /**
     * Starts a countdown timer with the specified initial duration.
     */
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

    /**
     * Stops the currently running countdown timer and resets its state.
     */
    fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countDownEnabled.value = false
        _countDownSeconds.value = 0
    }

    /**
     * Formats a duration in seconds into a human-readable time string.
     */
    @SuppressLint("DefaultLocale")
    private fun formatDuration(diffInSeconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(diffInSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(diffInSeconds) % 60
        val seconds = diffInSeconds % 60
        return if (hours <= 0) String.format("%02d:%02d", minutes, seconds)
        else String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}