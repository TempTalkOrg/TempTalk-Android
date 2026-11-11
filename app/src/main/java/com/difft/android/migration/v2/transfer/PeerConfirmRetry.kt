package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.PrepareUploadRejectedException
import java.io.IOException
import kotlinx.coroutines.delay









object PeerConfirmRetry {
    const val INTERVAL_MS = 5_000L
    const val BUDGET_MS = 5 * 60_000L

    



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
