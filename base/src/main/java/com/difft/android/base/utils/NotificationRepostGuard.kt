package com.difft.android.base.utils

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton












@Singleton
class NotificationRepostGuard internal constructor(
    private val context: Context,
    private val nowMs: () -> Long,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, System::currentTimeMillis)

    private val lock = Any()

    
    private val tombstones = HashMap<Int, Long>()

    
    
    
    private val timedExpiry = HashMap<Int, Pair<Long, Long>>()

    private val nm: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    
    fun onPosted(id: Int) {
        synchronized(lock) {
            tombstones.remove(id)
            timedExpiry.remove(id)
        }
    }

    




    fun cancel(id: Int) {
        synchronized(lock) {
            addTombstoneLocked(id)
            timedExpiry.remove(id)
            nm.cancel(id)
        }
    }

    
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
            
            val timeoutMs = sbn.notification.timeoutAfter
            if (timeoutMs > 0) {
                val now = nowMs()
                
                timedExpiry.entries.removeAll { it.key != id && it.value.first <= now }
                val (expiry, originalTimeoutMs) =
                    timedExpiry.getOrPut(id) { (sbn.postTime + timeoutMs) to timeoutMs }
                
                val remainingMs = (expiry - now).coerceAtMost(originalTimeoutMs)
                if (remainingMs <= 0) {
                    L.i { "[NotificationRepostGuard] repost skipped reason=expiring id=$id" }
                    return@synchronized false
                }
                builder.setTimeoutAfter(remainingMs)
            }
            val rebuilt = builder.build()
            
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
