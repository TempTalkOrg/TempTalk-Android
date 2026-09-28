package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides what a receive that stopped short is reported as, the way the iOS receiver does:
 *  - a sender that names its session on `POST /cancel` left on purpose → the source is gone;
 *  - a link that dropped is held for [cancelGraceMs] first, because a sender's cancel notice follows
 *    its hang-up on the wire — cancelling the upload closes the socket at once, the `/cancel` is a
 *    second request — so the drop is what arrives first even when the peer cancelled;
 *  - a sender that prepared but never opened its upload within [uploadStartTimeoutMs] is a lost link;
 *  - anything else (a gate reject, a corrupt container) is reported at once.
 *
 * [fail] receives the reason string `classifyFailure` reads. Calls arrive from server and import
 * threads, hence the locking; the timers are children of [scope], so a cancelled role cancels them.
 */
internal class ReceiverFailureArbiter(
    private val scope: CoroutineScope,
    private val fail: (String) -> Unit,
    private val cancelGraceMs: Long = CANCEL_GRACE_MS,
    private val uploadStartTimeoutMs: Long = UPLOAD_START_TIMEOUT_MS,
) {
    private var pendingDrop: Job? = null
    private var uploadWatchdog: Job? = null

    /** The sender's `prepare-upload` was accepted; its upload must follow. */
    @Synchronized
    fun onPrepared() {
        uploadWatchdog?.cancel()
        uploadWatchdog = scope.launch {
            delay(uploadStartTimeoutMs)
            L.w { "[MigrationV2][P2P][Rx] no upload within ${uploadStartTimeoutMs}ms of prepare — the sender is gone" }
            fail(DROPPED_LINK)
        }
    }

    /** The sender opened its upload: the watchdog has nothing left to watch. */
    @Synchronized
    fun onUploadStarted() {
        uploadWatchdog?.cancel()
        uploadWatchdog = null
    }

    /** The sender named its session on `/cancel`: it left on purpose, whatever the link did first. */
    @Synchronized
    fun onPeerCancelled() {
        clear()
        fail(PEER_LEFT)
    }

    /** A failure from the server or the import. A dropped link waits for a cancel notice that may follow. */
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

    /** Drops every pending verdict; the role is over. */
    @Synchronized
    fun clear() {
        pendingDrop?.cancel()
        pendingDrop = null
        uploadWatchdog?.cancel()
        uploadWatchdog = null
    }

    companion object {
        const val CANCEL_GRACE_MS = 1_500L

        /** The budget iOS gives a sender between `prepare-upload` and opening its upload. */
        const val UPLOAD_START_TIMEOUT_MS = 30_000L
        val DROPPED_LINK = "failed: ${MigrationError.ConnectionLost.code}"
        val PEER_LEFT = "failed: ${MigrationError.SourceUnavailable.code}"
    }
}
