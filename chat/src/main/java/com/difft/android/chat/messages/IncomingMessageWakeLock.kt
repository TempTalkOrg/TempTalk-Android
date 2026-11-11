package com.difft.android.chat.messages

import android.content.Context
import android.os.PowerManager
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
















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

    
    private var lastQueued = 0L

    
    fun onQueued(): Long = synchronized(lock) {
        acquire(HOLD_TIMEOUT_MS)
        ++lastQueued
    }

    
    fun onBatchStart() = synchronized(lock) {
        acquire(HOLD_TIMEOUT_MS)
    }

    




    fun onProcessed(lastSeq: Long) = synchronized(lock) {
        if (lastSeq >= lastQueued) acquire(GRACE_MS)
    }

    internal companion object {
        
        const val HOLD_TIMEOUT_MS = 30_000L

        
        const val GRACE_MS = 5_000L

        private const val TAG = "TempTalk:incoming-message"
    }
}
