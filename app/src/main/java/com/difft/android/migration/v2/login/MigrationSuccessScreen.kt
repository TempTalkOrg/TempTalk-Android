package com.difft.android.migration.v2.login

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.difft.android.R
import com.difft.android.migration.ui.component.MigrationAction
import com.difft.android.migration.ui.component.MigrationResultBody
import com.difft.android.migration.ui.component.MigrationResultPage
import com.difft.android.migration.ui.component.MigrationResultTone

/**
 * The new device's completion page: success mark, title, one line and a single button into the app.
 * Stateless; [onStart] is the only exit.
 */
@Composable
fun MigrationSuccessScreen(
    onStart: () -> Unit,
    // The session came across with the data (same-device no-login migration), so the copy can say
    // the account moved rather than only the history.
    credentialHandoff: Boolean = false,
) {
    MigrationResultPage(
        tone = MigrationResultTone.SUCCESS,
        title = stringResource(R.string.migration_p2_done_title),
        primary = MigrationAction(text = stringResource(R.string.migration_p2_done), onClick = onStart),
    ) {
        MigrationResultBody(
            stringResource(
                if (credentialHandoff) R.string.migration_p2_new_app_body else R.string.migration_p2_receiver_body,
            ),
        )
    }
}
