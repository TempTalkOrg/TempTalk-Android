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

    /**
     * Whether the call duration timer is actually running. 1v1 keeps the call in CONNECTED while
     * still waiting for the remote's transport to come up, so the UI needs to tell "connected but
     * not yet counting" apart from "counting" instead of rendering a frozen 00:00.
     */
    private val _callTimerRunning = MutableStateFlow(false)
    val callTimerRunning: StateFlow<Boolean> = _callTimerRunning.asStateFlow()

    private val _countDownEnabled = MutableStateFlow(false)
    val countDownEnabled: StateFlow<Boolean> = _countDownEnabled.asStateFlow()

    // speech countdown (seconds)
    private val _countDownSeconds = MutableStateFlow(0L)
    val countDownSeconds: StateFlow<Long> = _countDownSeconds.asStateFlow()

    /**
     * Wall-clock instant the call connected; 0 while no call timer is running. Volatile because
     * it is written on the main thread but read by [getCurrentDuration] from the cleanup
     * executor's IO dispatcher.
     */
    @Volatile
    private var callConnectedAtMillis = 0L

    /**
     * Starts a call duration timer that tracks and updates the elapsed call time.
     *
     * @param connectedAtMillis wall-clock instant the call connected, the same value the
     * ongoing-call notification publishes as `when`.
     */
    fun startCallTimer(connectedAtMillis: Long, onTick: (String) -> Unit) {
        if (callTimerJob != null) return
        callConnectedAtMillis = connectedAtMillis
        _callTimerRunning.value = true
        callTimerJob = scope.launch {
            while (true) {
                delayToNextSecondBoundary()
                // Recomputed from the connect instant rather than accumulated. delay() on the main
                // dispatcher schedules on uptimeMillis, which does not advance while the CPU is
                // suspended and does not make up a late resumption, so `value + 1` banked every
                // overrun permanently and fell behind the status-bar call chip — which counts wall
                // clock from this same instant. Recomputing self-corrects on the next tick.
                val sec = elapsedSeconds()
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
        callConnectedAtMillis = 0L
        _callTimerRunning.value = false
    }

    /**
     * Sleeps until the next whole second since the connect instant, instead of a flat 1000 ms.
     * Each iteration costs slightly more than its delay, so flat ticks slide later and later
     * relative to the instant the seconds are floored against, which eventually makes the display
     * jump two seconds at once. Re-aiming at the boundary every tick keeps it on one-second steps.
     */
    private suspend fun delayToNextSecondBoundary() {
        val connectedAt = callConnectedAtMillis
        if (connectedAt <= 0L) {
            delay(1000)
            return
        }
        val sinceConnect = System.currentTimeMillis() - connectedAt
        delay((1000 - sinceConnect.mod(1000L)).coerceIn(1L, 1000L))
    }

    /**
     * Seconds elapsed since the call connected. Clamped at 0 so a backwards wall-clock change
     * cannot render a negative duration.
     */
    private fun elapsedSeconds(): Long {
        val connectedAt = callConnectedAtMillis
        if (connectedAt <= 0L) return 0L
        return ((System.currentTimeMillis() - connectedAt) / 1000).coerceAtLeast(0L)
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

    /**
     * Retrieves the current call duration in seconds. Read live rather than from the last tick so
     * a caller landing between ticks — [com.difft.android.call.feedback.CallFeedbackBinder]'s
     * 60-second gate runs once at teardown — sees the true elapsed time.
     */
    fun getCurrentDuration(): Long {
        return if (callTimerJob != null) elapsedSeconds() else _callDurationSeconds.value
    }
}