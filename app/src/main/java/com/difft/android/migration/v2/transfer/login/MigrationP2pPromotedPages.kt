package com.difft.android.migration.v2.transfer.login

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.difft.android.R
import com.difft.android.migration.ui.component.MigrationAction
import com.difft.android.migration.ui.component.MigrationGap
import com.difft.android.migration.ui.component.MigrationProgressBlock
import com.difft.android.migration.ui.component.MigrationResultBody
import com.difft.android.migration.ui.component.MigrationResultPage
import com.difft.android.migration.ui.component.MigrationResultTone
import com.difft.android.migration.v2.login.MigrationSuccessScreen










sealed interface PromotedSignIn {
    
    data object Idle : PromotedSignIn

    
    data object Running : PromotedSignIn

    
    data object Done : PromotedSignIn

    





    data class Failed(val message: String?, val retryable: Boolean) : PromotedSignIn
}







@Composable
internal fun PromotedPages(
    phase: PromotedSignIn,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
    onContinueSignIn: () -> Unit,
) {
    
    
    
    BackHandler(enabled = phase !is PromotedSignIn.Failed) {
        
    }
    when (phase) {
        
        
        PromotedSignIn.Idle, PromotedSignIn.Running -> MigrationResultPage(
            tone = MigrationResultTone.INFO,
            title = stringResource(R.string.migration_p2_signing_in_title),
        ) {
            MigrationProgressBlock(progress = null, metric = null)
            MigrationGap()
            MigrationResultBody(stringResource(R.string.migration_p2_signing_in_body))
        }
        PromotedSignIn.Done -> MigrationSuccessScreen(onStart = onFinish)
        
        
        is PromotedSignIn.Failed -> MigrationResultPage(
            tone = MigrationResultTone.WARNING,
            title = stringResource(R.string.migration_p2_sign_in_failed_title),
            primary = if (phase.retryable) {
                MigrationAction(stringResource(R.string.migration_p2_retry_sign_in)) { onRetry() }
            } else {
                MigrationAction(stringResource(R.string.migration_p2_continue_sign_in)) { onContinueSignIn() }
            },
            secondary = if (phase.retryable) {
                MigrationAction(stringResource(R.string.migration_p2_continue_sign_in)) { onContinueSignIn() }
            } else {
                null
            },
        ) {
            MigrationResultBody(
                stringResource(
                    if (phase.retryable) R.string.migration_p2_sign_in_failed_body
                    else R.string.migration_p2_sign_in_failed_body_short
                )
            )
            phase.message?.takeIf { it.isNotBlank() }?.let {
                MigrationGap()
                MigrationResultBody(it)
            }
        }
    }
}
