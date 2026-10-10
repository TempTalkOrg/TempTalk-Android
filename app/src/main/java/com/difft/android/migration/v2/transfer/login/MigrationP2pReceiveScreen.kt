package com.difft.android.migration.v2.transfer.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.migration.ui.component.MigrationAction
import com.difft.android.migration.ui.component.MigrationBanner
import com.difft.android.migration.ui.component.MigrationBannerTone
import com.difft.android.migration.ui.component.MigrationBodyText
import com.difft.android.migration.ui.component.MigrationConfirmDialog
import com.difft.android.migration.ui.component.MigrationGap
import com.difft.android.migration.ui.component.MigrationNavIcon
import com.difft.android.migration.ui.component.MigrationPage
import com.difft.android.migration.ui.component.MigrationProgressBlock
import com.difft.android.migration.ui.component.MigrationReasonCard
import com.difft.android.migration.ui.component.MigrationResultBody
import com.difft.android.migration.ui.component.MigrationResultPage
import com.difft.android.migration.ui.component.MigrationResultTone
import com.difft.android.migration.ui.component.formatTransferMetric
import com.difft.android.migration.v2.transfer.send.MigrationFailureKind
import com.difft.android.migration.v2.transfer.send.classifyFailure

/**
 * The new device's receive flow: the pairing page with the QR shown to the old phone, the progress
 * page, and the failure page. Stateless, driven by [MigrationP2pReceiveState] and [onIntent]. The
 * transfer key is never rendered.
 *
 * System back is not a cancel: on the pairing page it returns to the source chooser, and once bytes
 * are moving it does nothing, since the login context behind this page would be lost. Only the
 * cancel button, through its confirmation, stops a transfer.
 */
@Composable
fun MigrationP2pReceiveScreen(
    state: MigrationP2pReceiveState,
    onIntent: (MigrationP2pReceiveIntent) -> Unit,
    // The peer is the old app on this phone, not another device: the copy says so throughout.
    sameDevice: Boolean = false,
) {
    // Swallowed only once bytes are moving or landing: before that (preparing, pairing, failed)
    // system back is a step back to the source chooser, never a dead end.
    val committed = state is MigrationP2pReceiveState.Receiving ||
        state == MigrationP2pReceiveState.Verifying ||
        state == MigrationP2pReceiveState.Activated ||
        state == MigrationP2pReceiveState.Promoted ||
        state == MigrationP2pReceiveState.CommittedUnconfirmed
    BackHandler(enabled = committed) {
        // Deliberately swallowed: see the class comment.
    }
    when (state) {
        MigrationP2pReceiveState.Starting -> PreparingPage(sameDevice = false, onIntent = onIntent)
        is MigrationP2pReceiveState.WaitingForPeer ->
            if (state.sameDevice) PreparingPage(sameDevice = true, onIntent = onIntent) else PairingPage(state, onIntent)
        // Reverse pairing: the computer has to confirm, then connect back. Same shape as the
        // preparing page — indeterminate progress and one line of what to do — with a cancel.
        is MigrationP2pReceiveState.AwaitingDesktopConfirm -> DesktopWaitPage(
            title = stringResource(R.string.migration_r2_desktop_confirm_title),
            hint = stringResource(R.string.migration_r2_desktop_confirm_hint),
            onIntent = onIntent,
        )
        is MigrationP2pReceiveState.AwaitingDesktopConnect -> DesktopWaitPage(
            title = stringResource(R.string.migration_r2_desktop_connect_title),
            hint = stringResource(R.string.migration_r2_desktop_connect_hint),
            onIntent = onIntent,
        )
        // The data is already this device's primary database; the Activity restarts at once so the
        // promote tail finishes on the next cold start. Nothing to act on here.
        MigrationP2pReceiveState.CommittedUnconfirmed -> MigrationResultPage(
            tone = MigrationResultTone.INFO,
            title = stringResource(R.string.migration_r2_finishing_restart),
        ) {
            MigrationProgressBlock(progress = null, metric = null)
        }
        is MigrationP2pReceiveState.Receiving -> ReceivingPage(state, sameDevice, onIntent)
        MigrationP2pReceiveState.Verifying, MigrationP2pReceiveState.Activated -> MigrationResultPage(
            tone = MigrationResultTone.INFO,
            title = stringResource(R.string.migration_p1_verifying_title),
        ) {
            MigrationProgressBlock(progress = 1f, metric = null)
            MigrationGap()
            MigrationResultBody(
                stringResource(
                    if (sameDevice) R.string.migration_p1_transferring_hint_same_device else R.string.migration_p1_transferring_hint,
                ),
            )
        }
        is MigrationP2pReceiveState.Error -> FailedPage(state, sameDevice, onIntent)
        // Terminal states are handled by the Activity (it finishes with a result code).
        MigrationP2pReceiveState.Promoted, MigrationP2pReceiveState.Skipped -> Box(Modifier.fillMaxSize())
    }
}

/**
 * The "preparing" page: the server is coming up, a Desktop code is being scanned, or the old app on
 * this device is showing its own confirmation page. The relaunch button covers the old app having
 * been dismissed; otherwise cancelling stops the listener and returns to the source chooser.
 */
@Composable
private fun PreparingPage(sameDevice: Boolean, onIntent: (MigrationP2pReceiveIntent) -> Unit) {
    var confirmCancel by remember { mutableStateOf(false) }
    MigrationResultPage(
        tone = MigrationResultTone.INFO,
        title = stringResource(R.string.migration_p1_preparing_title),
        secondary = if (sameDevice) {
            MigrationAction(stringResource(R.string.migration_p2p_receive_relaunch_legacy)) {
                onIntent(MigrationP2pReceiveIntent.RelaunchLegacySender)
            }
        } else {
            MigrationAction(stringResource(R.string.migration_p1_cancel), danger = true) { confirmCancel = true }
        },
    ) {
        MigrationProgressBlock(progress = null, metric = null)
        MigrationGap()
        MigrationResultBody(stringResource(R.string.migration_p1_preparing_hint))
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
                onIntent(MigrationP2pReceiveIntent.Leave)
            },
        )
    }
}

/**
 * The reverse-pairing waits: the computer's user confirming, then the computer connecting back.
 * Nothing is on the wire yet, so cancelling stops the pairing and returns to the source chooser.
 */
@Composable
private fun DesktopWaitPage(title: String, hint: String, onIntent: (MigrationP2pReceiveIntent) -> Unit) {
    var confirmCancel by remember { mutableStateOf(false) }
    MigrationResultPage(
        tone = MigrationResultTone.INFO,
        title = title,
        secondary = MigrationAction(stringResource(R.string.migration_p1_cancel), danger = true) { confirmCancel = true },
    ) {
        MigrationProgressBlock(progress = null, metric = null)
        MigrationGap()
        MigrationResultBody(hint)
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
                onIntent(MigrationP2pReceiveIntent.Cancel)
            },
        )
    }
}

/** The QR page: the code on its own white tile, then what to do and the network it needs. */
@Composable
private fun PairingPage(state: MigrationP2pReceiveState.WaitingForPeer, onIntent: (MigrationP2pReceiveIntent) -> Unit) {
    MigrationPage(
        title = stringResource(R.string.migration_r1_from_phone),
        navIcon = MigrationNavIcon.BACK,
        onNav = { onIntent(MigrationP2pReceiveIntent.Leave) },
        horizontalAlignment = Alignment.CenterHorizontally,
        scrollable = false,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(QrTileSize)
                // The code is the content: it sits on white in either theme, with no grey frame around it.
                .background(DifftTheme.colors.textOnPrimary, DifftTheme.radius.largeShape)
                // The bitmap has no margin; this padding is the 4-module quiet zone (45–49 modules in 168dp).
                .padding(QrQuietZone),
        ) {
            state.qrBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.migration_r1_from_phone),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        MigrationGap(24)
        MigrationBodyText(
            text = stringResource(R.string.migration_r2_qr_copy),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        MigrationGap()
        // A fixed slot, so the code does not jump when the notice comes and goes.
        Box(Modifier.height(RefreshNoticeSlot)) {
            if (state.refreshed) {
                MigrationBanner(
                    text = stringResource(R.string.migration_r2_qr_refreshed),
                    tone = MigrationBannerTone.INFO,
                    iconRes = R.drawable.migration_ic_info_circle,
                )
            }
        }
        Spacer(Modifier.weight(2f))
    }
}

/** The "transferring" page: the reading, the foreground rule, and the cancel button. */
@Composable
private fun ReceivingPage(
    state: MigrationP2pReceiveState.Receiving,
    sameDevice: Boolean,
    onIntent: (MigrationP2pReceiveIntent) -> Unit,
) {
    val context = LocalContext.current
    var confirmCancel by remember { mutableStateOf(false) }
    val progress = state.progress.takeIf { it in 0f..1f }
    MigrationResultPage(
        tone = MigrationResultTone.INFO,
        title = stringResource(R.string.migration_p1_transferring_title),
        secondary = MigrationAction(stringResource(R.string.migration_p1_cancel), danger = true) { confirmCancel = true },
    ) {
        MigrationProgressBlock(
            progress = progress,
            metric = progress?.let { formatTransferMetric(context, it, state.totalBytes) },
        )
        MigrationGap()
        MigrationResultBody(
            stringResource(
                if (sameDevice) R.string.migration_p1_transferring_hint_same_device else R.string.migration_p1_transferring_hint,
            ),
        )
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
                onIntent(MigrationP2pReceiveIntent.Cancel)
            },
        )
    }
}

/**
 * The failure page. Blocks that mean this path is closed — this install not empty, the switch off,
 * and on the same device an old app too old to speak the protocol — offer the other way to sign in.
 * A version block against another device instead offers this device's download page, since it is
 * this app that may need updating. Everything else offers a fresh start.
 */
@Composable
private fun FailedPage(
    state: MigrationP2pReceiveState.Error,
    sameDevice: Boolean,
    onIntent: (MigrationP2pReceiveIntent) -> Unit,
) {
    val kind = classifyFailure(state.reason)
    val legacyName = stringResource(R.string.migration_app_name_legacy)
    // A version block across devices can be this app's: the download page serves it, so the update
    // action stays. On the same device the peer is the old app, which only its own in-app check can
    // update, so that case is closed and the reason card is the instruction.
    val updatable = kind == MigrationFailureKind.UPDATE_NEEDED && !sameDevice
    val closed = kind == MigrationFailureKind.TARGET_NOT_EMPTY ||
        kind == MigrationFailureKind.HANDOFF_DISABLED ||
        (kind == MigrationFailureKind.UPDATE_NEEDED && sameDevice)
    val primary = when {
        updatable -> MigrationAction(stringResource(R.string.migration_m3_go)) { onIntent(MigrationP2pReceiveIntent.OpenUpdate) }
        closed -> MigrationAction(stringResource(R.string.migration_p3_other_login)) { onIntent(MigrationP2pReceiveIntent.Leave) }
        else -> MigrationAction(stringResource(R.string.migration_p3_retry)) { onIntent(MigrationP2pReceiveIntent.Retry) }
    }
    val secondary = MigrationAction(
        stringResource(if (closed) R.string.migration_p3_back else R.string.migration_common_cancel),
    ) { onIntent(MigrationP2pReceiveIntent.Leave) }
    val reason = when {
        // The receiving end of a same-device transfer names the app the data was coming from.
        kind.namesApp -> stringResource(kind.reasonRes, if (sameDevice) legacyName else stringResource(R.string.migration_app_name_new))
        else -> stringResource(kind.reasonRes)
    }
    MigrationResultPage(tone = kind.tone, title = stringResource(kind.titleRes), primary = primary, secondary = secondary) {
        MigrationReasonCard(label = stringResource(R.string.migration_p3_reason_label), reason = reason)
    }
}

private val QrTileSize = 200.dp
private val QrQuietZone = 16.dp
private val RefreshNoticeSlot = 56.dp
