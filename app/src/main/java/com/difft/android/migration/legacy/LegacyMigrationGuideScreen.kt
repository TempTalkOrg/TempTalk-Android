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


enum class LegacyGuideMode { GUIDE, MIGRATED }






enum class LegacyHandoffOffer {
    
    NONE,
    
    AVAILABLE,
    
    PEER_TOO_OLD,
    
    PEER_OCCUPIED,
}







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
        
        
        !installed -> MigrationAction(stringResource(R.string.legacy_migration_download, newApp), onClick = onDownload)
        handoff == LegacyHandoffOffer.PEER_OCCUPIED -> null
        handoff == LegacyHandoffOffer.PEER_TOO_OLD ->
            MigrationAction(stringResource(R.string.legacy_migration_handoff_cta, newApp)) { updateDialog = true }
        
        
        handoff == LegacyHandoffOffer.AVAILABLE ->
            MigrationAction(stringResource(R.string.legacy_migration_handoff_cta, newApp), onClick = onStartHandoff)
        else -> MigrationAction(stringResource(R.string.legacy_migration_handoff_cta, newApp), onClick = onOpenNewApp)
    }
    
    
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
