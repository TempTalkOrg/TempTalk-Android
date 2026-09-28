package com.difft.android.migration.legacy

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.difft.android.R
import com.difft.android.base.migration.PeerApps
import com.difft.android.migration.ui.component.MigrationAction
import com.difft.android.migration.ui.component.MigrationButtonStack
import com.difft.android.migration.ui.component.MigrationConfirmDialog
import com.difft.android.migration.ui.component.MigrationHero
import com.difft.android.migration.ui.component.MigrationPage
import com.difft.android.migration.ui.component.MigrationResultBody
import com.difft.android.migration.ui.component.MigrationResultPage
import com.difft.android.migration.ui.component.MigrationResultTone

/** Which page this is: the "move over" guide, or the "you already moved" end state. */
enum class LegacyGuideMode { GUIDE, MIGRATED }

/**
 * What the new app answered about itself, which decides the primary action on the guide page.
 * Everything unclear collapses into [NONE], where the button simply opens the new app and its
 * sign-in offers the history-only transfer.
 */
enum class LegacyHandoffOffer {
    /** No no-login offer: switched off here or there, unreachable, or nothing to hand over. */
    NONE,
    /** The new app is empty and up to date — offer to move the account across directly. */
    AVAILABLE,
    /** The new app predates the handoff protocol; it has to be updated first. */
    PEER_TOO_OLD,
    /** The new app already has an account or chat data, so it cannot receive one. */
    PEER_OCCUPIED,
}

/**
 * Why the move is needed, plus the one action that applies to the new app's install state: a hero
 * with the reason, one more line when the next step happens in the other app, and the button that
 * gets or opens it. No step list and no warning — the irreversible choice is confirmed in the new
 * app, where it is made. Stateless; [status] and [handoff] are re-read by the host on every resume.
 */
@Composable
fun LegacyMigrationGuideScreen(
    status: PeerApps.PeerStatus,
    onOpenNewApp: () -> Unit,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    mode: LegacyGuideMode = LegacyGuideMode.GUIDE,
    handoff: LegacyHandoffOffer = LegacyHandoffOffer.NONE,
    onStartHandoff: () -> Unit = {},
    onUpdatePeer: () -> Unit = {},
    onSignInAgain: () -> Unit = {},
) {
    if (mode == LegacyGuideMode.MIGRATED) {
        MigratedPage(onOpenNewApp, onSignInAgain)
        return
    }
    val newApp = stringResource(R.string.migration_app_name_new)
    val installed = status == PeerApps.PeerStatus.Trusted
    var updateDialog by remember { mutableStateOf(false) }
    val primary = when {
        // An app with the new app's name that is not ours counts as not installed; the download page
        // is the only safe next step and the reason stays in the log, not on screen.
        !installed -> MigrationAction(stringResource(R.string.legacy_migration_download, newApp), onClick = onDownload)
        handoff == LegacyHandoffOffer.PEER_OCCUPIED -> null
        handoff == LegacyHandoffOffer.PEER_TOO_OLD ->
            MigrationAction(stringResource(R.string.legacy_migration_handoff_cta, newApp)) { updateDialog = true }
        // One tap moves the account across: the new app opens its entry page and this app hands the
        // session over. Without the offer the new app still opens, into a history-only transfer.
        handoff == LegacyHandoffOffer.AVAILABLE ->
            MigrationAction(stringResource(R.string.legacy_migration_handoff_cta, newApp), onClick = onStartHandoff)
        else -> MigrationAction(stringResource(R.string.legacy_migration_handoff_cta, newApp), onClick = onOpenNewApp)
    }
    // Both install states read the same; only the button differs. The one extra line is the block
    // the new app reported, which no button can resolve.
    val nextStep = if (installed && handoff == LegacyHandoffOffer.PEER_OCCUPIED) {
        stringResource(R.string.legacy_migration_handoff_peer_occupied, newApp)
    } else {
        null
    }
    MigrationPage(
        scrollable = false,
        footer = {
            MigrationButtonStack(
                primary = primary,
                secondary = MigrationAction(stringResource(R.string.legacy_migration_later), onClick = onLater),
            )
        },
    ) {
        Spacer(Modifier.weight(1f))
        MigrationHero(
            title = stringResource(R.string.legacy_migration_title, newApp),
            description = stringResource(R.string.legacy_migration_why, newApp),
            secondaryDescription = nextStep,
        )
        Spacer(Modifier.weight(1f))
    }
    if (updateDialog) {
        MigrationConfirmDialog(
            title = stringResource(R.string.migration_m3_title),
            message = stringResource(R.string.migration_m3_body_peer, newApp),
            keepText = stringResource(R.string.migration_common_cancel),
            goText = stringResource(R.string.migration_m3_go),
            onKeep = { updateDialog = false },
            onGo = {
                updateDialog = false
                onUpdatePeer()
            },
        )
    }
}

/**
 * The session already moved. Re-login stays one tap away on purpose: it is a normal sign-in that
 * kicks the new app, exactly as a second device would.
 */
@Composable
private fun MigratedPage(onOpenNewApp: () -> Unit, onSignInAgain: () -> Unit) {
    MigrationResultPage(
        tone = MigrationResultTone.SUCCESS,
        title = stringResource(R.string.migration_p2_old_app_title, stringResource(R.string.migration_app_name_new)),
        primary = MigrationAction(stringResource(R.string.migration_p2_done), onClick = onOpenNewApp),
        secondary = MigrationAction(stringResource(R.string.migration_handoff_done_sign_in_again), onClick = onSignInAgain),
    ) {
        MigrationResultBody(stringResource(R.string.migration_p2_old_app_body, stringResource(R.string.migration_app_name_legacy)))
    }
}
