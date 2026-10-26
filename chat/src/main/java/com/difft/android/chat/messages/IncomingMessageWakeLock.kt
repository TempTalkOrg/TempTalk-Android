package com.difft.android.chat.messages

import android.content.Context
import android.os.PowerManager
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the CPU awake from the moment a WebSocket envelope is queued until its batch has been
 * processed (#1376).
 *
 * [IncomingEnvelopMessageProcessor] batches envelopes on a coroutine time window, which runs on the
 * uptime clock. Without a wakelock the CPU suspends again right after the network wake that
 * delivered the frame, and decryption, ACK and the notification wait for the next alarm (up to
 * one keep-alive cadence). Signal-Android holds a wakelock while draining its socket for the
 * same reason.
 *
 * One non-reference-counted partial wakelock: every [onQueued] and [onBatchStart] re-arms it for
 * [HOLD_TIMEOUT_MS]; once the newest queued envelope has been processed, [onProcessed] shortens it
 * to [GRACE_MS] so the notifications the batch launched without awaiting can be posted. The timeout is the only release path, so a lost
 * callback can never pin the CPU.
 */
@Singleton
class IncomingMessageWakeLock @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val wakeLock: PowerManager.WakeLock? by lazy {
        runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
                ?.apply { setReferenceCounted(false) }
        }.onFailure { L.w { "[Message] incoming wakelock create failed: ${it.message}" } }.getOrNull()
    }

    internal var acquire: (Long) -> Unit = { timeoutMs ->
        runCatching { wakeLock?.acquire(timeoutMs) }
            .onFailure { L.w { "[Message] incoming wakelock acquire failed: ${it.message}" } }
    }

    private val lock = Any()

    // Guarded by lock.
    private var lastQueued = 0L

    /** An envelope entered the batch queue; returns its sequence number for [onProcessed]. */
    fun onQueued(): Long = synchronized(lock) {
        acquire(HOLD_TIMEOUT_MS)
        ++lastQueued
    }

    /** A batch starts processing; its envelopes may outlast the hold armed when they were queued. */
    fun onBatchStart() = synchronized(lock) {
        acquire(HOLD_TIMEOUT_MS)
    }

    /**
     * A batch whose newest envelope had sequence [lastSeq] finished processing. Comparing
     * sequence numbers rather than counting keeps an envelope that never reached the batch (a
     * dropped emission) from holding the lock past the next processed batch.
     */
    fun onProcessed(lastSeq: Long) = synchronized(lock) {
        if (lastSeq >= lastQueued) acquire(GRACE_MS)
    }

    internal companion object {
        /** Upper bound per queued envelope; reached only if processing stalls. */
        const val HOLD_TIMEOUT_MS = 30_000L

        /** Kept after the queue drains for notifications the batch launched without awaiting. */
        const val GRACE_MS = 5_000L

        private const val TAG = "TempTalk:incoming-message"
    }
}
