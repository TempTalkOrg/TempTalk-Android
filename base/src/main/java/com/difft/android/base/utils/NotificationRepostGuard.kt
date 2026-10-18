package com.difft.android.base.utils

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes silent re-posts of incoming-call and critical-alert notifications against every
 * in-process cancel of the same id (#1347).
 *
 * A late duplicate FCM for an invite or alert that is still showing re-posts the notification with
 * `setOnlyAlertOnce(true)` so Android 13+ counts the push as having produced a notification.
 * `NotificationManager.cancel` is applied asynchronously, so an active-check followed by notify can
 * resurrect a notification the user just rejected. Cancel paths therefore record a tombstone under
 * the same lock before cancelling, and a repost requires no tombstone. A fresh post of the id calls
 * [onPosted] to clear the tombstone so a legitimate re-invite can be refreshed again.
 */
@Singleton
class NotificationRepostGuard internal constructor(
    private val context: Context,
    private val nowMs: () -> Long,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, System::currentTimeMillis)

    private val lock = Any()

    // id -> expiry (ms). Guarded by lock.
    private val tombstones = HashMap<Int, Long>()

    // id -> (expiry, original timeoutAfter) of a timed notification, captured at its first repost.
    // The system restarts timeoutAfter on every enqueue and postTime is not a reliable anchor across
    // updates, so later reposts count down from this. Guarded by lock.
    private val timedExpiry = HashMap<Int, Pair<Long, Long>>()

    private val nm: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** A fresh post of [id] was (or is about to be) made; any earlier cancel no longer applies. */
    fun onPosted(id: Int) {
        synchronized(lock) {
            tombstones.remove(id)
            timedExpiry.remove(id)
        }
    }

    /**
     * Records a tombstone for [id], then cancels it. Also used after a user swipe: the system
     * removal is not under this lock, so a repost may have restored the notification just before;
     * cancelling again under the lock removes it for good.
     */
    fun cancel(id: Int) {
        synchronized(lock) {
            addTombstoneLocked(id)
            timedExpiry.remove(id)
            nm.cancel(id)
        }
    }

    /** Tombstones every currently active id, then cancels all notifications. */
    fun cancelAll() {
        synchronized(lock) {
            try {
                nm.activeNotifications.forEach { addTombstoneLocked(it.id) }
            } catch (e: Exception) {
                L.w { "[NotificationRepostGuard] tombstone active failed error=${e.javaClass.simpleName}" }
            }
            nm.cancelAll()
        }
    }

    /**
     * Re-posts the active notification [id] silently. Returns true when a repost was issued.
     * Never posts when [id] is not active or was cancelled in-process within the tombstone TTL.
     */
    fun repostIfActive(id: Int): Boolean = synchronized(lock) {
        if (isTombstonedLocked(id)) {
            L.i { "[NotificationRepostGuard] repost skipped reason=cancelled id=$id" }
            return@synchronized false
        }
        try {
            val sbn = nm.activeNotifications.firstOrNull { it.id == id }
            if (sbn == null) {
                L.i { "[NotificationRepostGuard] repost skipped reason=inactive id=$id" }
                return@synchronized false
            }
            val builder = Notification.Builder.recoverBuilder(context, sbn.notification)
                .setOnlyAlertOnce(true)
            // The system restarts timeoutAfter on every enqueue: keep the original expiry.
            val timeoutMs = sbn.notification.timeoutAfter
            if (timeoutMs > 0) {
                val now = nowMs()
                // Prune other ids whose notification has expired (the system removed it on its own).
                timedExpiry.entries.removeAll { it.key != id && it.value.first <= now }
                val (expiry, originalTimeoutMs) =
                    timedExpiry.getOrPut(id) { (sbn.postTime + timeoutMs) to timeoutMs }
                // Capped: a wall clock moved backwards must not extend the original timeout.
                val remainingMs = (expiry - now).coerceAtMost(originalTimeoutMs)
                if (remainingMs <= 0) {
                    L.i { "[NotificationRepostGuard] repost skipped reason=expiring id=$id" }
                    return@synchronized false
                }
                builder.setTimeoutAfter(remainingMs)
            }
            val rebuilt = builder.build()
            // notify inside the lock so a concurrent cancel() cannot slip between check and post.
            nm.notify(sbn.tag, id, rebuilt)
            L.i { "[NotificationRepostGuard] reposted id=$id" }
            true
        } catch (e: Exception) {
            L.e { "[NotificationRepostGuard] repost failed id=$id error=${e.stackTraceToString()}" }
            false
        }
    }

    private fun addTombstoneLocked(id: Int) {
        val now = nowMs()
        tombstones.entries.removeAll { it.value <= now }
        tombstones[id] = now + TOMBSTONE_TTL_MS
    }

    private fun isTombstonedLocked(id: Int): Boolean {
        val expiry = tombstones[id] ?: return false
        if (expiry <= nowMs()) {
            tombstones.remove(id)
            return false
        }
        return true
    }

    companion object {
        internal const val TOMBSTONE_TTL_MS = 10 * 60 * 1000L
    }
}
