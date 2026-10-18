package com.difft.android.migration.v2.transfer.send

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.common.compose.ContactAvatar
import com.difft.android.chat.common.compose.LetterAvatar
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.migration.ui.component.MigrationAction
import com.difft.android.migration.ui.component.MigrationBodyText
import com.difft.android.migration.ui.component.MigrationCaption
import com.difft.android.base.migration.ui.MigrationCard
import com.difft.android.migration.ui.component.MigrationCardDivider
import com.difft.android.migration.ui.component.MigrationConfirmDialog
import com.difft.android.migration.ui.component.MigrationGap
import com.difft.android.migration.ui.component.MigrationIconTile
import com.difft.android.migration.ui.component.MigrationIdentityRow
import com.difft.android.migration.ui.component.MigrationNavIcon
import com.difft.android.migration.ui.component.MigrationPage
import com.difft.android.migration.ui.component.MigrationPrimaryButton
import com.difft.android.migration.ui.component.MigrationProgressBlock
import com.difft.android.migration.ui.component.MigrationReasonCard
import com.difft.android.migration.ui.component.MigrationResultBody
import com.difft.android.migration.ui.component.MigrationResultPage
import com.difft.android.migration.ui.component.MigrationResultTone
import com.difft.android.migration.ui.component.MigrationSwitchRow
import com.difft.android.migration.ui.component.MigrationTextButton
import com.difft.android.migration.ui.component.MigrationValueRow
import com.difft.android.migration.ui.component.formatMigrationBytes
import com.difft.android.migration.ui.component.formatTransferMetric
import com.difft.android.migration.v2.handoff.MigrationHandoffReconciler
import org.difft.app.database.models.ContactorModel

/**
 * The old device's send flow: the confirmation page, which is the only place data is authorised to
 * leave, the progress page, the completion page and the failure page. Stateless, driven by
 * [MigrationP2pSendState] and [onIntent]. The transfer key is never rendered.
 */
@Composable
fun MigrationP2pSendScreen(
    state: MigrationP2pSendState,
    onIntent: (MigrationP2pSendIntent) -> Unit,
    // The paired device's name, shown on the confirmation page.
    peerAlias: String? = null,
    // The receiver is the new app on this device: copy names it, and the exit returns to it.
    sameDevice: Boolean = false,
    // Opened straight after a Desktop link login: this device stays signed in whatever happens here.
    fromLink: Boolean = false,
    // The same-device no-login handoff: the login session itself travels.
    credentialHandoff: Boolean = false,
    // The signed-in account, for the confirmation page's identity row (null while loading).
    selfContact: ContactorModel? = null,
    selfId: String = "",
) {
    when (state) {
        MigrationP2pSendState.Idle -> IdlePage(onIntent)
        MigrationP2pSendState.Scanning -> BusyPage()
        MigrationP2pSendState.Authenticating -> ProgressPage(
            title = stringResource(R.string.migration_p1_preparing_title),
            metric = null,
            hint = stringResource(R.string.migration_handoff_auth_desc),
        )
        is MigrationP2pSendState.Blocked -> FailurePage(
            kind = classifyFailure(state.reason),
            primary = MigrationAction(stringResource(R.string.migration_p3_back)) { onIntent(MigrationP2pSendIntent.Close) },
            secondary = null,
        )
        is MigrationP2pSendState.Ready ->
            if (state.spaceSufficient) {
                ConfirmPage(state, peerAlias, sameDevice || credentialHandoff, selfContact, selfId, onIntent)
            } else {
                SpaceBlockPage(state, onIntent)
            }
        // No reading before bytes move: the one sentence under the bar says what is happening.
        MigrationP2pSendState.Preparing -> ProgressPage(
            title = stringResource(R.string.migration_p1_preparing_title),
            metric = null,
            hint = stringResource(R.string.migration_p1_preparing_hint),
            cancel = { onIntent(MigrationP2pSendIntent.Cancel) },
        )
        MigrationP2pSendState.AwaitingPeerConfirm -> AwaitingDesktopPage(peerAlias, onIntent)
        is MigrationP2pSendState.Transferring -> {
            val context = LocalContext.current
            val progress = state.progress.takeIf { it in 0f..1f }
            ProgressPage(
                title = stringResource(R.string.migration_p1_transferring_title),
                progress = progress,
                metric = progress?.let { formatTransferMetric(context, it, state.totalBytes) },
                hint = stringResource(
                    if (sameDevice) R.string.migration_p1_transferring_hint_same_device else R.string.migration_p1_transferring_hint,
                ),
                cancel = { onIntent(MigrationP2pSendIntent.Cancel) },
            )
        }
        MigrationP2pSendState.AwaitingVerdict -> ProgressPage(
            title = stringResource(R.string.migration_p1_finishing_title),
            metric = null,
            // A Desktop only links; this device stays the primary, so nothing is about to happen to it.
            hint = if (fromLink) null else stringResource(R.string.migration_p1_finishing_hint),
            // The wait has no upper bound the user can feel — the peer may verify and import for
            // minutes — so this page must offer the same way out the ongoing notification already
            // does. Without it the only exit is the system back gesture, which abandons the transfer
            // to the service with nothing on screen ever pointing back at it.
            cancel = { onIntent(MigrationP2pSendIntent.Cancel) },
        )
        is MigrationP2pSendState.Unconfirmed -> UnconfirmedPage(state, onIntent)
        MigrationP2pSendState.Done -> DonePage(sameDevice, onIntent)
        is MigrationP2pSendState.Failed -> FailedPage(state, fromLink, credentialHandoff, onIntent)
        // The Activity finishes on this; nothing to draw for the frame it takes.
        MigrationP2pSendState.Cancelled -> Box(Modifier.fillMaxSize())
    }
}

/** Reached only when the scanner was dismissed: offer to scan again, or leave. */
@Composable
private fun IdlePage(onIntent: (MigrationP2pSendIntent) -> Unit) {
    MigrationPage(
        title = stringResource(R.string.migration_r1_title),
        navIcon = MigrationNavIcon.BACK,
        onNav = { onIntent(MigrationP2pSendIntent.Close) },
        footer = {
            MigrationPrimaryButton(
                text = stringResource(R.string.migration_p2p_send_scan_button),
                onClick = { onIntent(MigrationP2pSendIntent.LaunchScan) },
            )
        },
    ) {
        MigrationBodyText(stringResource(R.string.migration_r2_network))
    }
}

@Composable
private fun BusyPage() {
    MigrationPage(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        CircularProgressIndicator()
        Spacer(Modifier.weight(1f))
    }
}

/** The confirmation page: target, account, size, the attachment choice, and the button that starts it. */
@Composable
private fun ConfirmPage(
    state: MigrationP2pSendState.Ready,
    peerAlias: String?,
    toNewApp: Boolean,
    selfContact: ContactorModel?,
    selfId: String,
    onIntent: (MigrationP2pSendIntent) -> Unit,
) {
    val context = LocalContext.current
    val estimate = state.estimate
    MigrationPage(
        title = stringResource(R.string.migration_r1_title),
        navIcon = MigrationNavIcon.BACK,
        onNav = { onIntent(MigrationP2pSendIntent.Close) },
        footer = {
            MigrationPrimaryButton(
                text = stringResource(R.string.migration_s1_start),
                // Size and space have to be known before the user can weigh what leaves this device.
                enabled = estimate != null,
                onClick = { onIntent(MigrationP2pSendIntent.Send) },
            )
        },
    ) {
        MigrationBodyText(
            if (toNewApp) {
                stringResource(R.string.migration_handoff_send_target, stringResource(R.string.migration_app_name_new))
            } else {
                stringResource(R.string.migration_s1_target, peerAlias ?: state.alias)
            },
        )
        MigrationGap()
        MigrationCard {
            MigrationIdentityRow(
                title = selfContact?.getDisplayNameWithoutRemarkForUI() ?: selfId,
                subtitle = stringResource(R.string.migration_s1_current_account),
            ) {
                if (selfContact != null) {
                    ContactAvatar(contactor = selfContact, size = 40.dp)
                } else {
                    LetterAvatar(firstLetter = selfId.take(1), id = selfId, size = 40.dp)
                }
            }
        }
        MigrationGap()
        if (estimate == null) {
            CalculatingCard()
        } else {
            val bytes = if (state.includeAttachments || !state.canDropAttachments) estimate.totalBytes else estimate.withoutAttachmentsBytes
            MigrationCard {
                MigrationValueRow(
                    label = stringResource(R.string.migration_s1_size_label),
                    value = formatMigrationBytes(context, bytes),
                )
                // Attachments are a choice only above the threshold; below it they travel.
                if (state.canDropAttachments) {
                    MigrationCardDivider()
                    MigrationSwitchRow(
                        title = stringResource(R.string.migration_s1_include_attachments),
                        checked = state.includeAttachments,
                        onCheckedChange = { onIntent(MigrationP2pSendIntent.SetIncludeAttachments(it)) },
                    )
                }
            }
            if (state.canDropAttachments) {
                MigrationGap()
                MigrationCaption(
                    stringResource(R.string.migration_s1_attachments_note, formatMigrationBytes(context, estimate.attachmentBytes)),
                )
            }
        }
    }
}

@Composable
private fun CalculatingCard() {
    MigrationCard(padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.migration_s1_calculating),
                style = DifftTheme.typography.p3,
                color = DifftTheme.colors.textTertiary,
            )
        }
    }
}

/** Block page: the target volume cannot take the payload. Nothing has started, so the mark is a warning. */
@Composable
private fun SpaceBlockPage(state: MigrationP2pSendState.Ready, onIntent: (MigrationP2pSendIntent) -> Unit) {
    val context = LocalContext.current
    MigrationResultPage(
        tone = MigrationResultTone.WARNING,
        title = stringResource(R.string.migration_p3_space_title),
        primary = MigrationAction(stringResource(R.string.migration_p3_manage_storage)) {
            onIntent(MigrationP2pSendIntent.OpenStorageSettings)
        },
        // Leaving attachments behind is a remedy only when the user was offered the choice at all.
        secondary = if (state.canDropAttachments && state.includeAttachments) {
            MigrationAction(stringResource(R.string.migration_p3_drop_attachments)) {
                onIntent(MigrationP2pSendIntent.SetIncludeAttachments(false))
            }
        } else {
            MigrationAction(stringResource(R.string.migration_common_cancel)) { onIntent(MigrationP2pSendIntent.Close) }
        },
    ) {
        MigrationReasonCard(
            label = stringResource(R.string.migration_p3_space_reason_label),
            reason = stringResource(R.string.migration_p3_reason_space, formatMigrationBytes(context, state.spaceShortfallBytes)),
        )
    }
}

/**
 * The progress page. A non-null [cancel] means the phase can still be stopped, through a
 * confirmation; phases past the point of no return pass none and show no button.
 */
@Composable
private fun ProgressPage(
    title: String,
    metric: String?,
    hint: String?,
    progress: Float? = null,
    cancel: (() -> Unit)? = null,
) {
    var confirmCancel by remember { mutableStateOf(false) }
    MigrationResultPage(
        tone = MigrationResultTone.INFO,
        title = title,
        secondary = cancel?.let {
            MigrationAction(stringResource(R.string.migration_p1_cancel), danger = true) { confirmCancel = true }
        },
    ) {
        MigrationProgressBlock(progress = progress, metric = metric)
        if (hint != null) {
            MigrationGap()
            MigrationResultBody(hint)
        }
    }
    if (confirmCancel && cancel != null) {
        MigrationConfirmDialog(
            title = stringResource(R.string.migration_m1_title),
            message = stringResource(R.string.migration_m1_body),
            keepText = stringResource(R.string.migration_m1_keep),
            goText = stringResource(R.string.migration_m1_go),
            goDanger = true,
            onKeep = { confirmCancel = false },
            onGo = {
                confirmCancel = false
                cancel()
            },
        )
    }
}

/**
 * Desktop link, account switch: the Desktop is asking its user to confirm before it accepts the
 * upload. Rendered in the shape of the sign-in confirmation page it followed — the target computer,
 * one line saying where to look, and a disabled sign-in button.
 */
@Composable
private fun AwaitingDesktopPage(peerAlias: String?, onIntent: (MigrationP2pSendIntent) -> Unit) {
    MigrationPage(
        title = stringResource(R.string.link_login_title),
        footer = {
            MigrationPrimaryButton(text = stringResource(R.string.link_login_sign_in), enabled = false, onClick = {})
            Spacer(Modifier.height(8.dp))
            MigrationTextButton(
                text = stringResource(R.string.migration_common_cancel),
                onClick = { onIntent(MigrationP2pSendIntent.Cancel) },
            )
        },
    ) {
        MigrationCard {
            MigrationIdentityRow(
                title = peerAlias ?: stringResource(R.string.link_login_device_generic),
                subtitle = null,
            ) {
                MigrationIconTile(R.drawable.migration_ic_device_desktop)
            }
        }
        MigrationGap()
        MigrationBodyText(stringResource(R.string.link_login_waiting_note))
    }
}

/** Completion page: the old app has moved over, or the history reached the new device. */
@Composable
private fun DonePage(sameDevice: Boolean, onIntent: (MigrationP2pSendIntent) -> Unit) {
    if (sameDevice) {
        MigrationResultPage(
            tone = MigrationResultTone.SUCCESS,
            title = stringResource(R.string.migration_p2_old_app_title, stringResource(R.string.migration_app_name_new)),
            // The new app is mid-activation behind us; the way out is back into it.
            primary = MigrationAction(stringResource(R.string.migration_p2_done)) { onIntent(MigrationP2pSendIntent.ReturnToPeer) },
        ) {
            MigrationResultBody(stringResource(R.string.migration_p2_old_app_body, stringResource(R.string.migration_app_name_legacy)))
        }
    } else {
        // The 200 only means the import was promoted: the receiver has not signed in yet, so this
        // device is still the valid session. Keep it until the user has checked the new device.
        MigrationResultPage(
            tone = MigrationResultTone.SUCCESS,
            title = stringResource(R.string.migration_p2_done_title),
            primary = MigrationAction(stringResource(R.string.migration_p2_ok)) { onIntent(MigrationP2pSendIntent.Close) },
        ) {
            MigrationResultBody(stringResource(R.string.migration_p2_sender_transferred_body))
        }
    }
}

/**
 * The failure page for a send. The primary action depends on how the send can be restarted: on the
 * same-device handoff by asking the new app for a fresh pairing, gated on the new app's answer; on the
 * link path and cross-device by scanning a fresh code. A retry never resumes a failed session in
 * place — the receiver's pairing code belongs to one session, and a computer that cancelled or timed
 * out has dropped its session too.
 */
@Composable
private fun FailedPage(
    state: MigrationP2pSendState.Failed,
    fromLink: Boolean,
    credentialHandoff: Boolean,
    onIntent: (MigrationP2pSendIntent) -> Unit,
) {
    val kind = classifyFailure(state.reason)
    val primary = when {
        kind == MigrationFailureKind.UPDATE_NEEDED ->
            MigrationAction(stringResource(R.string.migration_m3_go)) { onIntent(MigrationP2pSendIntent.OpenUpdate) }
        // A failed hand-over may still have been completed by the new app, so starting over is only
        // safe once it has said it did not. Until then: recovering, or a re-check when it cannot be reached.
        credentialHandoff && state.reconciling ->
            MigrationAction(stringResource(R.string.migration_p3_recovering), enabled = false) {}
        credentialHandoff && state.reconcileOutcome == MigrationHandoffReconciler.Outcome.UNKNOWN ->
            MigrationAction(stringResource(R.string.migration_p3_recheck)) { onIntent(MigrationP2pSendIntent.RecheckPeer) }
        // Same device: the pairing payload died with the new app's receiver, so the retry asks it
        // for a fresh one rather than re-sending a code nothing is listening on.
        credentialHandoff ->
            MigrationAction(stringResource(R.string.migration_p3_retry), enabled = state.retryEnabled) {
                onIntent(MigrationP2pSendIntent.RefreshPairing)
            }
        // The computer shows a fresh code once its session ends; the home scanner takes it from there.
        fromLink -> MigrationAction(stringResource(R.string.migration_p3_rescan)) { onIntent(MigrationP2pSendIntent.RescanLink) }
        // The receiver's pairing code is session-scoped, so a retry means scanning a new one.
        else -> MigrationAction(stringResource(R.string.migration_p3_retry)) { onIntent(MigrationP2pSendIntent.LaunchScan) }
    }
    FailurePage(
        kind = kind,
        primary = primary,
        secondary = MigrationAction(stringResource(R.string.migration_common_cancel)) { onIntent(MigrationP2pSendIntent.Close) },
    )
}

@Composable
private fun FailurePage(kind: MigrationFailureKind, primary: MigrationAction?, secondary: MigrationAction?) {
    MigrationResultPage(tone = kind.tone, title = stringResource(kind.titleRes), primary = primary, secondary = secondary) {
        MigrationReasonCard(
            label = stringResource(R.string.migration_p3_reason_label),
            reason = if (kind.namesApp) {
                stringResource(kind.reasonRes, stringResource(R.string.migration_app_name_new))
            } else {
                stringResource(kind.reasonRes)
            },
        )
    }
}
