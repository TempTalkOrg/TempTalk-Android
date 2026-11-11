package com.difft.android.migration.v2.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L






class MigrationTransferCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MigrationTransferNotificationHelper.ACTION_CANCEL) {
            L.i { "[MigrationV2][P2P][FGS] received cancel broadcast" }
            MigrationTransferService.cancel(context)
        }
    }
}
