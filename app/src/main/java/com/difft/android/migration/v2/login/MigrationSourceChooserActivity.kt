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












@AndroidEntryPoint
class MigrationSourceChooserActivity : BaseActivity() {

    @Inject
    lateinit var pendingLoginStore: PendingLoginStore

    private val account: String by lazy {
        intent.getStringExtra(MigrationImportLauncherImpl.EXTRA_ACCOUNT).orEmpty()
    }

    
    private val pendingSignIn: LoginViewModel.PendingSignIn? by lazy {
        IntentCompat.getParcelableExtra(
            intent,
            MigrationImportLauncherImpl.EXTRA_PENDING_SIGN_IN,
            LoginViewModel.PendingSignIn::class.java,
        )
    }

    



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
                BackHandler {  }
                MigrationSourceChooserScreen(
                    onPhone = { openPairing(scanDesktop = false) },
                    onDesktop = { openPairing(scanDesktop = true) },
                    onSkip = { answer(MigrationImportLauncher.RESULT_SKIPPED) },
                )
            }
        }
    }

    




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
