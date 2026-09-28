package com.difft.android.migration.v2.handoff

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.difft.android.R
import com.difft.android.migration.ui.component.MigrationAction
import com.difft.android.migration.ui.component.MigrationButtonStack
import com.difft.android.migration.ui.component.MigrationConfirmDialog
import com.difft.android.migration.ui.component.MigrationHero
import com.difft.android.migration.ui.component.MigrationPage
import com.difft.android.migration.ui.component.MigrationReasonCard
import com.difft.android.migration.ui.component.MigrationResultPage
import com.difft.android.migration.ui.component.MigrationResultTone

/**
 * The new package's first-launch page: brand mark, one question and two buttons. "Use another
 * sign-in method" goes through the irreversible-skip confirmation, because everyone who sees this
 * page has history that would otherwise be left behind. Blocked states and a too-old old app render
 * as the shared block page instead.
 */
@Composable
fun MigrationHandoffEntryScreen(
    state: MigrationHandoffEntryState,
    onIntent: (MigrationHandoffEntryIntent) -> Unit,
) {
    BackHandler { onIntent(MigrationHandoffEntryIntent.Back) }
    val legacyName = stringResource(R.string.migration_app_name_legacy)
    var confirmOtherLogin by remember { mutableStateOf(false) }
    when (state) {
        MigrationHandoffEntryState.Loading -> MigrationPage(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator()
            Spacer(Modifier.weight(1f))
        }

        MigrationHandoffEntryState.Offer -> MigrationPage(
            scrollable = false,
            footer = {
                MigrationButtonStack(
                    primary = MigrationAction(stringResource(R.string.migration_handoff_entry_primary, legacyName)) {
                        onIntent(MigrationHandoffEntryIntent.ChooseHandoff)
                    },
                    secondary = MigrationAction(stringResource(R.string.migration_handoff_entry_secondary)) {
                        confirmOtherLogin = true
                    },
                )
            },
        ) {
            Spacer(Modifier.weight(1f))
            MigrationHero(
                title = stringResource(R.string.migration_handoff_entry_title, legacyName),
                description = stringResource(R.string.migration_handoff_entry_body),
            )
            Spacer(Modifier.weight(1f))
        }

        MigrationHandoffEntryState.PeerTooOld -> MigrationResultPage(
            tone = MigrationResultTone.WARNING,
            title = stringResource(R.string.migration_m3_title),
            // The old package has no store listing to open, so the reason card is the whole
            // instruction and the only action left is the other way to sign in.
            primary = MigrationAction(stringResource(R.string.migration_handoff_entry_secondary)) {
                onIntent(MigrationHandoffEntryIntent.ChooseNormalLogin)
            },
        ) {
            MigrationReasonCard(
                label = stringResource(R.string.migration_p3_reason_label),
                reason = stringResource(R.string.migration_m3_body_peer, legacyName),
            )
        }

        is MigrationHandoffEntryState.Blocked -> MigrationResultPage(
            tone = MigrationResultTone.WARNING,
            title = stringResource(R.string.migration_p3_blocked_title),
            primary = MigrationAction(stringResource(R.string.migration_handoff_entry_secondary)) {
                onIntent(MigrationHandoffEntryIntent.ChooseNormalLogin)
            },
            secondary = MigrationAction(stringResource(R.string.migration_p3_back)) { onIntent(MigrationHandoffEntryIntent.Back) },
        ) {
            MigrationReasonCard(
                label = stringResource(R.string.migration_p3_reason_label),
                reason = when (state.reason) {
                    HandoffEntryBlockReason.PEER_NO_ACCOUNT -> stringResource(R.string.migration_p3_reason_no_account, legacyName)
                    HandoffEntryBlockReason.TARGET_NOT_EMPTY -> stringResource(R.string.migration_handoff_target_not_empty)
                },
            )
        }
    }
    if (confirmOtherLogin) {
        MigrationConfirmDialog(
            title = stringResource(R.string.migration_m2_title),
            message = stringResource(R.string.migration_m2_body_same_device, legacyName),
            keepText = stringResource(R.string.migration_m2_keep),
            goText = stringResource(R.string.migration_m2_go),
            goDanger = true,
            onKeep = { confirmOtherLogin = false },
            onGo = {
                confirmOtherLogin = false
                onIntent(MigrationHandoffEntryIntent.ChooseNormalLogin)
            },
        )
    }
}
