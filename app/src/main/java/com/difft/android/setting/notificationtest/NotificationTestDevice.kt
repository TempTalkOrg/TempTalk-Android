package com.difft.android.setting.notificationtest

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/** The device state the notification test reads while it runs (#1350): Do Not Disturb, lock state, screen state. */
class NotificationTestDevice @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun isDndOn(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return NotificationTestRules.isDndOn(nm.currentInterruptionFilter)
    }

    fun isKeyguardLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Whether a PIN, pattern, password or biometric lock is set; read for diagnostics only. */
    fun isDeviceSecure(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Whether the screen is on; unknown counts as on, so an unreadable state never extends a run. */
    fun isInteractive(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive != false

    /**
     * Keeps the CPU awake for at most [timeoutMs] so the screen-off phase of a test is not stalled by
     * suspend; returns the release action. A missing PowerManager or a refused lock degrades to no lock.
     */
    fun holdWakeLock(timeoutMs: Long): () -> Unit {
        val lock = try {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply { acquire(timeoutMs) }
        } catch (e: RuntimeException) {
            L.w { "[NotificationTest] wake lock failed error=${e.javaClass.simpleName}" }
            null
        }
        return {
            // The timeout can release the lock between isHeld and release(); that race only throws.
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: RuntimeException) {
                L.w { "[NotificationTest] wake lock release failed error=${e.javaClass.simpleName}" }
            }
        }
    }

    /** Emits each time the screen turns off; the receiver lives only while the flow is collected. */
    fun screenOffEvents(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "TempTalk:NotificationTest"
    }
}
