package com.difft.android.migration.v2.transfer

import android.content.Context
import com.difft.android.R
import com.difft.android.migration.v2.transfer.send.MigrationFailureKind
import com.difft.android.migration.v2.transfer.send.classifyFailure

/**
 * The notification a transfer leaves behind when its service goes away: what the user sees in the
 * tray if they were not looking at the screen when it ended. One per terminal state; a cancel by
 * the user leaves none.
 */
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
            TransferState.Unknown -> notificationHelper.showTerminal(
                context.getString(R.string.migration_p2p_notification_title_unconfirmed),
                context.getString(R.string.migration_p2p_notification_text_unconfirmed),
            )
            TransferState.Cancelled -> { /* user cancelled — no terminal notification needed */ }
            else -> { /* non-terminal at teardown (e.g. exception before state set) — skip */ }
        }
    }

    /**
     * Maps a [TransferState.Failed] reason to user-facing copy. `reason` is a diagnostic string, not
     * a closed enum: a handshake-gate wire token, the local pairing-timeout marker, or an arbitrary
     * transport message. Known cases get the same localized strings the transfer screens use; an
     * unrecognized reason falls through to the raw string rather than dropping the only diagnostic
     * the user can quote back to support.
     */
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
