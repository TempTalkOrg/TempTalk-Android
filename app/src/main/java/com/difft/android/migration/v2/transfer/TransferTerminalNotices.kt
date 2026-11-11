package com.difft.android.migration.v2.transfer

import android.content.Context
import com.difft.android.R
import com.difft.android.migration.v2.transfer.send.MigrationFailureKind
import com.difft.android.migration.v2.transfer.send.classifyFailure






internal class TransferTerminalNotices(
    private val context: Context,
    private val notificationHelper: MigrationTransferNotificationHelper,
) {
    fun post(state: TransferState) {
        when (state) {
            TransferState.Promoted -> notificationHelper.showTerminal(
                context.getString(R.string.migration_p2p_notification_title_success),
                context.getString(R.string.migration_p2p_notification_text_restored),
            )
            TransferState.Sent -> notificationHelper.showTerminal(
                context.getString(R.string.migration_p2p_notification_title_success),
                context.getString(R.string.migration_p2p_notification_text_uploaded),
            )
            is TransferState.Failed -> notificationHelper.showTerminal(
                context.getString(R.string.migration_p2p_notification_title_failure),
                localizedFailureReason(state.reason),
            )
            
            
            TransferState.Unknown, TransferState.CommittedUnconfirmed -> notificationHelper.showTerminal(
                context.getString(R.string.migration_p2p_notification_title_unconfirmed),
                context.getString(R.string.migration_p2p_notification_text_unconfirmed),
            )
            TransferState.Cancelled -> {  }
            else -> {  }
        }
    }

    






    fun localizedFailureReason(reason: String): String {
        if (reason == MigrationTransferService.REASON_PAIRING_TIMEOUT) {
            return context.getString(R.string.migration_p2p_notification_text_pairing_timeout)
        }
        val kind = classifyFailure(reason)
        return when {
            kind == MigrationFailureKind.UNKNOWN -> reason
            kind.namesApp -> context.getString(kind.reasonRes, context.getString(R.string.migration_app_name_new))
            else -> context.getString(kind.reasonRes)
        }
    }
}
