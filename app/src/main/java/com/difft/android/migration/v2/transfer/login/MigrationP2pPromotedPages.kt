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

/**
 * Where the sign-in that follows a promoted import stands, as the receive page shows it. The page
 * itself finishes the sign-in — the same chain the code page runs — so the user goes from
 * "transfer complete" to the chat list without seeing the verification page again.
 *
 * The sign-in starts on its own as soon as the import is promoted; the completion page comes after
 * it, not before, so "Transfer Complete" is only claimed once the account is actually on this
 * device.
 */
sealed interface PromotedSignIn {
    /** The history is on disk and the sign-in is about to start; no user action is awaited. */
    data object Idle : PromotedSignIn

    /** Renewing (when a transfer token was issued) and signing in, in place. */
    data object Running : PromotedSignIn

    /** Signed in: the completion page waits for "Done", which restarts into the migrated account. */
    data object Done : PromotedSignIn

    /**
     * The sign-in did not finish. The data stays; the user verifies again and registers directly.
     *
     * @param retryable the request got no answer, so the held token is unspent and the same sign-in
     *   may be run again. A server verdict is never retryable — the same token gets the same answer.
     */
    data class Failed(val message: String?, val retryable: Boolean) : PromotedSignIn
}

/**
 * @param onFinish "Done" on the completion page — the restart into the migrated account.
 * @param onRetry "Try Again" on the failure page — re-runs the sign-in with the unspent token. Only
 *   offered when the failure was a transport failure.
 * @param onContinueSignIn "Verify Account Again" — hands back to the code page for a fresh code.
 */
@Composable
internal fun PromotedPages(
    phase: PromotedSignIn,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
    onContinueSignIn: () -> Unit,
) {
    // The data is committed and a sign-in is in flight or done: system back must not drop the login
    // context behind this page. The failure page keeps its ordinary back — its two buttons are not
    // the only way out of a sign-in that will not complete.
    BackHandler(enabled = phase !is PromotedSignIn.Failed) {
        // Deliberately swallowed: see above.
    }
    when (phase) {
        // Idle is momentary — the sign-in starts as soon as the state lands — so it renders the same
        // page as Running rather than flashing a different one.
        PromotedSignIn.Idle, PromotedSignIn.Running -> MigrationResultPage(
            tone = MigrationResultTone.INFO,
            title = stringResource(R.string.migration_p2_signing_in_title),
        ) {
            MigrationProgressBlock(progress = null, metric = null)
            MigrationGap()
            MigrationResultBody(stringResource(R.string.migration_p2_signing_in_body))
        }
        PromotedSignIn.Done -> MigrationSuccessScreen(onStart = onFinish)
        // Retry reuses the unspent token after a transport failure; a fresh code is the only way out
        // of a server refusal, so that page offers it alone rather than a retry that must fail again.
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
