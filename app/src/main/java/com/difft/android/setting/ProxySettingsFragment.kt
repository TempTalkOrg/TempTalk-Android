package com.difft.android.setting

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import com.difft.android.base.ui.compose.DifftScreen
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ToastUtil
import com.difft.android.setting.viewmodel.ProxySettingsViewModel
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.UiEvent
import dagger.hilt.android.AndroidEntryPoint

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.utils.pane.*

@AndroidEntryPoint
class ProxySettingsFragment : Fragment(), SettingsIdiomPage {
    private val viewModel: ProxySettingsViewModel by viewModels()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    ProxySettingsScreen(viewModel,
                        onBack = { detailNavigation().pop(this@ProxySettingsFragment) },
                        onToast = { ToastUtil.show(getString(it)) },
                        visibleOwner = detailBackOwner(), padsBottom = containerPadsBottom())
                }
            }
        }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        detailKeyboard(showOnFirstOpen = false, focus = {}, savedInstanceState = savedInstanceState)
    }
}

object ProxySettingsDestination : DetailDestination {
    override fun newFragment(): Fragment = ProxySettingsFragment()
    override fun shellIntent(context: Context): Intent = Intent(context, ProxySettingsActivity::class.java)
}

@Composable
private fun ProxySettingsScreen(
    viewModel: ProxySettingsViewModel,
    onBack: () -> Unit,
    onToast: (Int) -> Unit,
    visibleOwner: androidx.lifecycle.LifecycleOwner,
    padsBottom: Boolean,
) {
    val state by viewModel.uiState.collectAsState()
    val visibleState by visibleOwner.lifecycle.currentStateFlow.collectAsState()

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
        visibleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { handleEvent(it) }
        }
    }

    Box(Modifier.consumeContainerInsets(padsBottom)) {
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
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            )
        }
    }

    ProxyPassphraseDialog(
        isVisible = showPassphraseDialog && visibleState.isAtLeast(Lifecycle.State.STARTED),
        isError = passphraseError,
        isSaving = state.isSaving,
        onConfirm = { passphrase -> viewModel.saveWithPassphrase(passphrase) },
        onDismiss = { if (!state.isSaving) showPassphraseDialog = false },
    )
}
