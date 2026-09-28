package com.difft.android.login

import android.os.SystemClock
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The resend link's countdown. While it runs the link is disabled and shows the seconds left; at
 * zero it becomes tappable again. Only a settled resend request restarts it — see
 * `VerifyCodeActivity.onResendResult`.
 *
 * Runs in [scope], so the screen's own scope ends the countdown and no pending tick can reach
 * [label] past destruction.
 *
 * Counts down to a deadline on [now], not by summing ticks: its default,
 * [SystemClock.elapsedRealtime], keeps counting through deep sleep, so a device that sleeps
 * mid-countdown re-enables the link on time when it wakes instead of resuming where it left off,
 * and a device time change cannot skew it either. Tests pass their own clock.
 */
internal class ResendCountdown(
    private val label: TextView,
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {

    private var job: Job? = null

    /** Disabled with no seconds shown — the state before the first code is sent. */
    fun reset() = render(enabled = false, seconds = 0)

    fun start() {
        job?.cancel()
        job = scope.launch {
            val deadline = now() + DURATION_MILLIS
            while (true) {
                val remaining = deadline - now()
                if (remaining <= 0) break
                // Each tick waits out the remainder above the next whole second, so every displayed
                // value is on screen for a whole second and the last one is not cut short. A wake
                // from deep sleep lands past several such boundaries and catches up in one step.
                render(enabled = false, seconds = ceilSeconds(remaining))
                delay(((remaining - 1) % TICK_MILLIS) + 1)
            }
            render(enabled = true, seconds = 0)
        }
    }

    private fun ceilSeconds(millis: Long): Int = ((millis + TICK_MILLIS - 1) / TICK_MILLIS).toInt()

    private fun render(enabled: Boolean, seconds: Int) {
        val context = label.context
        label.isEnabled = enabled
        label.setTextColor(
            ContextCompat.getColor(
                context,
                if (enabled) com.difft.android.base.R.color.t_info else com.difft.android.base.R.color.t_disable
            )
        )
        label.text = context.getString(R.string.login_email_code_resend, if (enabled) "" else "(${seconds}s)")
    }

    private companion object {
        const val DURATION_MILLIS = 60_000L
        const val TICK_MILLIS = 1000L
    }
}
