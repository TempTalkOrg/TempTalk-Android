package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch













internal class ReceiverFailureArbiter(
    private val scope: CoroutineScope,
    private val fail: (String) -> Unit,
    private val cancelGraceMs: Long = CANCEL_GRACE_MS,
    private val uploadStartTimeoutMs: Long = UPLOAD_START_TIMEOUT_MS,
) {
    private var pendingDrop: Job? = null
    private var uploadWatchdog: Job? = null

    
    @Synchronized
    fun onPrepared() {
        uploadWatchdog?.cancel()
        uploadWatchdog = scope.launch {
            delay(uploadStartTimeoutMs)
            L.w { "[MigrationV2][P2P][Rx] no upload within ${uploadStartTimeoutMs}ms of prepare — the sender is gone" }
            fail(DROPPED_LINK)
        }
    }

    
    @Synchronized
    fun onUploadStarted() {
        uploadWatchdog?.cancel()
        uploadWatchdog = null
    }

    
    @Synchronized
    fun onPeerCancelled() {
        clear()
        fail(PEER_LEFT)
    }

    
    @Synchronized
    fun onFailure(reason: String) {
        uploadWatchdog?.cancel()
        uploadWatchdog = null
        if (!reason.contains(MigrationError.ConnectionLost.code)) {
            pendingDrop?.cancel()
            pendingDrop = null
            fail(reason)
            return
        }
        if (pendingDrop?.isActive == true) return
        pendingDrop = scope.launch {
            delay(cancelGraceMs)
            fail(reason)
        }
    }

    
    @Synchronized
    fun clear() {
        pendingDrop?.cancel()
        pendingDrop = null
        uploadWatchdog?.cancel()
        uploadWatchdog = null
    }

    companion object {
        const val CANCEL_GRACE_MS = 1_500L

        
        const val UPLOAD_START_TIMEOUT_MS = 30_000L
        val DROPPED_LINK = "failed: ${MigrationError.ConnectionLost.code}"
        val PEER_LEFT = "failed: ${MigrationError.SourceUnavailable.code}"
    }
}
