package com.difft.android.migration.v2.transfer.link

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.migration.ui.component.MigrationBodyText
import com.difft.android.migration.ui.component.MigrationCaption
import com.difft.android.migration.ui.component.MigrationCard
import com.difft.android.migration.ui.component.MigrationCardDivider
import com.difft.android.migration.ui.component.MigrationGap
import com.difft.android.migration.ui.component.MigrationIconTile
import com.difft.android.migration.ui.component.MigrationIdentityRow
import com.difft.android.migration.ui.component.MigrationNavIcon
import com.difft.android.migration.ui.component.MigrationPage
import com.difft.android.migration.ui.component.MigrationPrimaryButton
import com.difft.android.migration.ui.component.MigrationRow
import com.difft.android.migration.ui.component.MigrationSwitchRow
import com.difft.android.migration.ui.component.MigrationTextButton
import com.difft.android.migration.ui.component.MigrationValueRow
import com.difft.android.migration.ui.component.formatMigrationBytes
import com.difft.android.migration.v2.transfer.send.classifyFailure
import com.difft.android.p2p.engine.model.LinkPeerState

/**
 * The "sign in to Quicall for Desktop" confirmation page: the target computer as a card, then one
 * block that is either the sync decision or a single statement of why there is none, and the
 * sign-in / cancel pair.
 */
@Composable
fun LinkLoginScreen(state: LinkLoginState, onIntent: (LinkLoginIntent) -> Unit) {
    MigrationPage(
        title = stringResource(R.string.link_login_title),
        navIcon = MigrationNavIcon.CLOSE,
        onNav = { onIntent(LinkLoginIntent.Cancel) },
        footer = {
            when (state) {
                is LinkLoginState.Compound -> CompoundActions(state, onIntent)
                is LinkLoginState.Plain -> MigrationPrimaryButton(
                    text = stringResource(R.string.link_login_sign_in),
                    onClick = { onIntent(LinkLoginIntent.SignIn) },
                    enabled = !state.busy,
                )
            }
            Spacer(Modifier.height(8.dp))
            MigrationTextButton(text = stringResource(R.string.migration_common_cancel), onClick = { onIntent(LinkLoginIntent.Cancel) })
        },
    ) {
        MigrationCard {
            MigrationIdentityRow(
                // A plain sign-in code names no computer; the compound one carries the Desktop's alias.
                title = (state as? LinkLoginState.Compound)?.alias ?: stringResource(R.string.link_login_device_generic),
                subtitle = null,
            ) {
                MigrationIconTile(R.drawable.migration_ic_device_desktop)
            }
        }
        MigrationGap()
        when (state) {
            is LinkLoginState.Compound -> CompoundBlock(state, onIntent)
            is LinkLoginState.Plain -> MigrationBodyText(
                stringResource(
                    when (state.kind) {
                        PlainKind.LEGACY_DESKTOP -> R.string.link_login_legacy_desktop_note
                        PlainKind.UNAVAILABLE -> R.string.link_login_unavailable_note
                    },
                ),
            )
        }
        if (state.isBusy()) {
            MigrationGap(24)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

/** The one block: a statement when there is nothing to decide, otherwise the sync card and what follows it. */
@Composable
private fun CompoundBlock(state: LinkLoginState.Compound, onIntent: (LinkLoginIntent) -> Unit) {
    when {
        state.reachable == false -> MigrationBodyText(stringResource(R.string.link_login_unreachable_note))
        state.gateReject != null -> MigrationBodyText(stringResource(classifyFailure(state.gateReject).reasonRes))
        // The Desktop already holds this account's history: it keeps it, nothing syncs, no switch.
        state.peerState == LinkPeerState.SAME_ACCOUNT -> MigrationBodyText(stringResource(R.string.link_login_same_account_note))
        else -> SyncCard(state, onIntent)
    }
    // Another account's history is on the Desktop: its user will be asked to delete it first.
    if (state.peerState == LinkPeerState.DIFFERENT_ACCOUNT && state.gateReject == null && state.reachable != false) {
        MigrationGap()
        MigrationCaption(stringResource(R.string.link_login_different_account_note))
    }
}

@Composable
private fun SyncCard(state: LinkLoginState.Compound, onIntent: (LinkLoginIntent) -> Unit) {
    val estimate = state.estimate
    val context = LocalContext.current
    // The attachment note only follows a card that actually shows the attachment switch.
    val attachmentBytes = estimate?.takeIf { state.syncEnabled && it.offersAttachmentChoice }?.attachmentBytes
    MigrationCard {
        MigrationSwitchRow(
            title = stringResource(R.string.link_login_sync_switch),
            // Off has a consequence worth stating; on only repeats the label, so it gets none.
            subtitle = if (state.syncEnabled) null else stringResource(R.string.link_login_sync_off_note),
            checked = state.syncEnabled,
            onCheckedChange = { onIntent(LinkLoginIntent.SetSync(it)) },
            // Off means there is no transfer: the size and attachment rows go away rather than greying out.
            enabled = state.reachable != null,
        )
        if (state.syncEnabled) {
            MigrationCardDivider()
            if (estimate == null) {
                MigrationRow {
                    Text(
                        text = stringResource(R.string.migration_s1_calculating),
                        style = DifftTheme.typography.p3,
                        color = DifftTheme.colors.textTertiary,
                    )
                }
            } else {
                val bytes = if (state.includeAttachments || !estimate.offersAttachmentChoice) estimate.totalBytes else estimate.withoutAttachmentsBytes
                MigrationValueRow(
                    // "Sync", not "transfer": this phone keeps everything.
                    label = stringResource(R.string.link_login_size_label),
                    value = formatMigrationBytes(context, bytes),
                )
                // Attachments are only a choice above the threshold; below it they simply travel.
                if (estimate.offersAttachmentChoice) {
                    MigrationCardDivider()
                    MigrationSwitchRow(
                        title = stringResource(R.string.migration_s1_include_attachments),
                        checked = state.includeAttachments,
                        onCheckedChange = { onIntent(LinkLoginIntent.SetIncludeAttachments(it)) },
                    )
                }
            }
        }
    }
    if (attachmentBytes != null) {
        MigrationGap()
        MigrationCaption(stringResource(R.string.migration_s1_attachments_note, formatMigrationBytes(context, attachmentBytes)))
    }
}

@Composable
private fun CompoundActions(state: LinkLoginState.Compound, onIntent: (LinkLoginIntent) -> Unit) {
    val primaryText = if (state.willSync) R.string.link_login_sign_in_and_sync else R.string.link_login_sign_in
    MigrationPrimaryButton(
        text = stringResource(primaryText),
        onClick = { onIntent(if (state.gateReject != null) LinkLoginIntent.SignInWithoutSync else LinkLoginIntent.SignIn) },
        // Probing decides whether "and sync" is even on offer; a tap before it answers would be a guess.
        enabled = !state.busy && state.reachable != null,
    )
}

private fun LinkLoginState.isBusy(): Boolean = when (this) {
    is LinkLoginState.Compound -> busy
    is LinkLoginState.Plain -> busy
}
