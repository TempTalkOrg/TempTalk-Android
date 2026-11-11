package com.difft.android.login

import android.os.SystemClock
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch














internal class ResendCountdown(
    private val label: TextView,
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {

    private var job: Job? = null

    
    fun reset() = render(enabled = false, seconds = 0)

    fun start() {
        job?.cancel()
        job = scope.launch {
            val deadline = now() + DURATION_MILLIS
            while (true) {
                val remaining = deadline - now()
                if (remaining <= 0) break
                
                
                
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
                if (enabled) com.difft.android.base.R.color.primary else com.difft.android.base.R.color.t_disable
            )
        )
        label.text = context.getString(R.string.login_email_code_resend, if (enabled) "" else "(${seconds}s)")
    }

    private companion object {
        const val DURATION_MILLIS = 60_000L
        const val TICK_MILLIS = 1000L
    }
}
