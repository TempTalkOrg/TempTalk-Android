package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.compose.DifftScreen
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ToastUtil
import com.difft.android.setting.viewmodel.ProxySettingsViewModel
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.UiEvent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ProxySettingsActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Context) {
            activity.startActivity(Intent(activity, ProxySettingsActivity::class.java))
        }
    }

    private val viewModel: ProxySettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DifftTheme {
                ProxySettingsScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onToast = { resId -> ToastUtil.show(getString(resId)) },
                )
            }
        }
    }
}

@Composable
private fun ProxySettingsScreen(
    viewModel: ProxySettingsViewModel,
    onBack: () -> Unit,
    onToast: (Int) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    // Saveable: a rotation while the passphrase prompt is up must not drop the prompt.
    var showPassphraseDialog by rememberSaveable { mutableStateOf(false) }
    var passphraseError by rememberSaveable { mutableStateOf(false) }

    fun handleEvent(event: UiEvent) {
        when (event) {
            UiEvent.Saved -> {
                showPassphraseDialog = false
                onToast(R.string.proxy_saved)
            }

            UiEvent.NeedPassphrase -> {
                passphraseError = false
                showPassphraseDialog = true
            }

            // Keep the dialog open, surface the error AND toast so the user can retry.
            UiEvent.WrongPassphrase -> {
                passphraseError = true
                onToast(R.string.proxy_passphrase_wrong_toast)
            }

            is UiEvent.Toast -> {
                showPassphraseDialog = false
                onToast(event.resId)
            }
        }
    }

    // Save runs off the main thread (PBKDF2 decrypt); outcomes arrive here.
    LaunchedEffect(Unit) {
        viewModel.events.collect { handleEvent(it) }
    }

    Box(Modifier.systemBarsPadding()) {
        DifftScreen(
            title = stringResource(R.string.proxy_settings_title),
            onNavigateBack = onBack,
            containerColor = DifftTheme.colors.bg,
        ) { padding ->
            ProxySettingsContent(
                state = state,
                onUseProxyChange = { viewModel.onUseProxyChange(it) },
                onProtectCallIpChange = { viewModel.onProtectCallIpChange(it) },
                onAddressChange = { viewModel.onAddressChange(it) },
                onRecheck = { viewModel.checkConnectivity() },
                onSave = { viewModel.save() },
                modifier = Modifier.padding(padding),
            )
        }
    }

    ProxyPassphraseDialog(
        isVisible = showPassphraseDialog,
        isError = passphraseError,
        isSaving = state.isSaving,
        onConfirm = { passphrase -> viewModel.saveWithPassphrase(passphrase) },
        onDismiss = { if (!state.isSaving) showPassphraseDialog = false },
    )
}
