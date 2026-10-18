package com.difft.android.migration.v2.login

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.migration.ui.MigrationCard
import com.difft.android.migration.ui.component.MigrationCardDivider
import com.difft.android.migration.ui.component.MigrationConfirmDialog
import com.difft.android.migration.ui.component.MigrationGap
import com.difft.android.migration.ui.component.MigrationHero
import com.difft.android.migration.ui.component.MigrationOptionRow
import com.difft.android.migration.ui.component.MigrationPage
import com.difft.android.migration.ui.component.MigrationTextButton
import com.difft.android.login.entry.PendingLoginStore
import com.difft.android.login.viewmodel.LoginViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The source chooser the new device shows after the code is verified and before it signs in:
 * another phone or a computer. Tapping a card opens the matching pairing page **from here**, so this
 * page stays on the back stack underneath it: backing out of the pairing page lands straight back on
 * the chooser, with no verify screen flashing in between. The only way past without a transfer is
 * the bottom text button, behind the irreversible-skip confirmation. System back is swallowed: the
 * verify screen behind this page has nothing to go back to.
 *
 * Only two answers ever reach the login flow: the restore that owes a sign-in, and the confirmed
 * skip. Everything else is a return to this page.
 */
@AndroidEntryPoint
class MigrationSourceChooserActivity : BaseActivity() {

    @Inject
    lateinit var pendingLoginStore: PendingLoginStore

    private val account: String by lazy {
        intent.getStringExtra(MigrationImportLauncherImpl.EXTRA_ACCOUNT).orEmpty()
    }

    /** The verified sign-in the pairing page finishes once the data is promoted; absent on a resume. */
    private val pendingSignIn: LoginViewModel.PendingSignIn? by lazy {
        IntentCompat.getParcelableExtra(
            intent,
            MigrationImportLauncherImpl.EXTRA_PENDING_SIGN_IN,
            LoginViewModel.PendingSignIn::class.java,
        )
    }

    /**
     * The pairing page, launched for result. Only a promoted restore whose sign-in did not finish is
     * passed up; a skip or a back out of that page is simply this page becoming visible again.
     */
    private val receiveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == MigrationImportLauncher.RESULT_RESTORED) {
            answer(MigrationImportLauncher.RESULT_RESTORED)
        } else {
            L.i { "[MigrationV2][SourceChooser] pairing page returned ${result.resultCode} → staying on the chooser" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        L.i { "[MigrationV2][SourceChooser] shown" }
        setContent {
            DifftTheme {
                BackHandler { /* the code is verified; the only exits are the choices on this page */ }
                MigrationSourceChooserScreen(
                    onPhone = { openPairing(scanDesktop = false) },
                    onDesktop = { openPairing(scanDesktop = true) },
                    onSkip = { answer(MigrationImportLauncher.RESULT_SKIPPED) },
                )
            }
        }
    }

    /**
     * Open the pairing page with the verified sign-in, which it finishes itself once the data is
     * promoted. The same sign-in is also held on disk first, so a process that dies during the
     * transfer can still finish on the next cold start instead of asking for a code again.
     */
    private fun openPairing(scanDesktop: Boolean) {
        L.i { "[MigrationV2][SourceChooser] opening the pairing page scanDesktop=$scanDesktop" }
        val pending = pendingSignIn
        if (pending != null) lifecycleScope.launch { pendingLoginStore.save(pending) }
        receiveLauncher.launch(
            MigrationImportLauncherImpl.createP2pReceiveIntentFor(this, account, scanDesktop = scanDesktop).apply {
                if (pending != null) putExtra(MigrationImportLauncherImpl.EXTRA_PENDING_SIGN_IN, pending)
            },
        )
    }

    private fun answer(resultCode: Int) {
        L.i { "[MigrationV2][SourceChooser] answer=$resultCode" }
        setResult(resultCode)
        finish()
    }
}

@Composable
fun MigrationSourceChooserScreen(
    onPhone: () -> Unit,
    onDesktop: () -> Unit,
    onSkip: () -> Unit,
) {
    var confirmSkip by remember { mutableStateOf(false) }
    // A decision page, not a list: the hero and the card sit in the upper half, the exit at the bottom.
    MigrationPage(
        scrollable = false,
        footer = {
            MigrationTextButton(text = stringResource(R.string.migration_r1_skip), onClick = { confirmSkip = true })
        },
    ) {
        Spacer(Modifier.weight(1f))
        MigrationHero(
            title = stringResource(R.string.migration_r1_title),
            description = stringResource(R.string.migration_r1_body),
        )
        MigrationGap()
        MigrationCard {
            MigrationOptionRow(
                title = stringResource(R.string.migration_r1_from_phone),
                iconRes = R.drawable.migration_ic_device_mobile,
                onClick = onPhone,
            )
            MigrationCardDivider()
            MigrationOptionRow(
                title = stringResource(R.string.migration_r1_from_desktop),
                iconRes = R.drawable.migration_ic_device_desktop,
                onClick = onDesktop,
            )
        }
        Spacer(Modifier.weight(2f))
    }
    if (confirmSkip) {
        MigrationConfirmDialog(
            title = stringResource(R.string.migration_m2_title),
            message = stringResource(R.string.migration_m2_body_cross),
            keepText = stringResource(R.string.migration_m2_keep),
            goText = stringResource(R.string.migration_m2_go),
            goDanger = true,
            onKeep = { confirmSkip = false },
            onGo = {
                confirmSkip = false
                onSkip()
            },
        )
    }
}
