package com.difft.android.migration.v2.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L

/**
 * Receives the transfer notification's Cancel broadcast and forwards it to
 * [MigrationTransferService.cancel]. Registered non-exported in the manifest with an intent filter
 * matching [MigrationTransferNotificationHelper.ACTION_CANCEL].
 */
class MigrationTransferCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MigrationTransferNotificationHelper.ACTION_CANCEL) {
            L.i { "[MigrationV2][P2P][FGS] received cancel broadcast" }
            MigrationTransferService.cancel(context)
        }
    }
}
