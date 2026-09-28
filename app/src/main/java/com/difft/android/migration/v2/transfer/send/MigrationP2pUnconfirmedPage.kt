package com.difft.android.migration.v2.transfer.send

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.difft.android.R
import com.difft.android.migration.ui.component.MigrationAction
import com.difft.android.migration.ui.component.MigrationConfirmDialog
import com.difft.android.migration.ui.component.MigrationGap
import com.difft.android.migration.ui.component.MigrationResultBody
import com.difft.android.migration.ui.component.MigrationResultPage
import com.difft.android.migration.ui.component.MigrationResultTone
import com.difft.android.migration.ui.component.MigrationTextButton
import com.difft.android.migration.v2.transfer.send.MigrationP2pSendState.Unconfirmed.Phase

/**
 * The sender's "result unconfirmed" page. Not a failure page: nothing here says the migration
 * failed, and nothing offers to try again, because the one thing this device does not know is
 * whether the new device finished — and starting over while it did would put two devices on one
 * account. The page waits with the receiver while it is still being polled, offers one more
 * question once it is not, and otherwise sends the user to look at the new device. The only way to
 * a fresh start is the explicit "start over" the user takes after looking there.
 */
@Composable
internal fun UnconfirmedPage(state: MigrationP2pSendState.Unconfirmed, onIntent: (MigrationP2pSendIntent) -> Unit) {
    val waiting = state.phase == Phase.WAITING
    var confirmCancel by remember { mutableStateOf(false) }
    MigrationResultPage(
        tone = MigrationResultTone.WARNING,
        title = stringResource(R.string.migration_p4_unconfirmed_title),
        primary = when (state.phase) {
            Phase.WAITING -> null
            Phase.CHECKING -> MigrationAction(stringResource(R.string.migration_p4_checking), enabled = false) {}
            Phase.IDLE, Phase.UNREACHABLE ->
                MigrationAction(stringResource(R.string.migration_p3_recheck)) { onIntent(MigrationP2pSendIntent.RecheckVerdict) }
        },
        secondary = if (waiting) {
            MigrationAction(stringResource(R.string.migration_p1_cancel), danger = true) { confirmCancel = true }
        } else {
            MigrationAction(stringResource(R.string.migration_p4_close)) { onIntent(MigrationP2pSendIntent.Close) }
        },
    ) {
        MigrationResultBody(
            stringResource(
                when (state.phase) {
                    Phase.WAITING -> R.string.migration_p4_waiting_body
                    Phase.IDLE, Phase.CHECKING -> R.string.migration_p4_idle_body
                    Phase.UNREACHABLE -> R.string.migration_p4_unreachable_body
                },
            ),
        )
        if (state.phase == Phase.IDLE || state.phase == Phase.UNREACHABLE) {
            MigrationGap()
            MigrationTextButton(
                text = stringResource(R.string.migration_p4_start_over),
                onClick = { onIntent(MigrationP2pSendIntent.DiscardUnconfirmed) },
            )
        }
    }
    if (confirmCancel) {
        MigrationConfirmDialog(
            title = stringResource(R.string.migration_m1_title),
            message = stringResource(R.string.migration_m1_body),
            keepText = stringResource(R.string.migration_m1_keep),
            goText = stringResource(R.string.migration_m1_go),
            goDanger = true,
            onKeep = { confirmCancel = false },
            onGo = {
                confirmCancel = false
                onIntent(MigrationP2pSendIntent.Cancel)
            },
        )
    }
}
