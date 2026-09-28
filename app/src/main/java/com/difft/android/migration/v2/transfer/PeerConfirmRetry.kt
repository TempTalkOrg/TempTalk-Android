package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.PrepareUploadRejectedException
import java.io.IOException
import kotlinx.coroutines.delay

/**
 * Retries `prepare-upload` while the Desktop is waiting for its user to confirm an account switch.
 * During that window the Desktop holds the request open with `102` heartbeats, so a transport
 * failure means a network hiccup rather than a refusal and is retried every [INTERVAL_MS] for up to
 * [BUDGET_MS]. A `401` here means the Desktop's user cancelled and the PIN was cleared, so it is
 * reported as [PrepareUploadReasons.PEER_CANCELLED] rather than as a stale code; every other typed
 * rejection passes through unchanged.
 */
object PeerConfirmRetry {
    const val INTERVAL_MS = 5_000L
    const val BUDGET_MS = 5 * 60_000L

    /**
     * @param onWaiting fired once, on the first transport failure, so the screen can say the Desktop
     *   is waiting for a confirmation instead of showing a stalled transfer.
     */
    suspend fun <T> run(
        onWaiting: () -> Unit,
        clock: () -> Long = System::currentTimeMillis,
        delayFn: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): T {
        val startedAt = clock()
        var announced = false
        while (true) {
            try {
                return block()
            } catch (e: PrepareUploadRejectedException) {
                if (e.reason.contains(PrepareUploadReasons.PIN_REQUIRED)) {
                    L.w { "[MigrationV2][P2P][Tx] prepare-upload 401 while awaiting the Desktop's confirmation — cancelled there" }
                    throw PrepareUploadRejectedException(PrepareUploadReasons.PEER_CANCELLED)
                }
                throw e
            } catch (e: IOException) {
                val elapsed = clock() - startedAt
                if (elapsed >= BUDGET_MS) {
                    L.w { "[MigrationV2][P2P][Tx] prepare-upload still failing after ${elapsed / 1000}s: ${e.message}" }
                    throw PrepareUploadRejectedException(PrepareUploadReasons.PEER_CONFIRM_TIMEOUT)
                }
                if (!announced) {
                    announced = true
                    onWaiting()
                }
                L.i { "[MigrationV2][P2P][Tx] prepare-upload not answered (${e.javaClass.simpleName}) — retrying in ${INTERVAL_MS / 1000}s" }
                delayFn(INTERVAL_MS)
            }
        }
    }
}
