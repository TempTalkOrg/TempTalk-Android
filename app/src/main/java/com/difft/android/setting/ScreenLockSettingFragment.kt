package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.R
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.settings.*
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.*
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import com.difft.android.base.widget.ToastUtil
import com.difft.android.login.BindAccountActivity
import com.difft.android.login.ScreenLockActivity
import com.difft.android.login.SecurityResultNonce
import com.difft.android.setting.mvi.ScreenLockContract
import com.difft.android.setting.mvi.ScreenLockContract.Stage
import com.difft.android.setting.mvi.ScreenLockSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ScreenLockSettingFragment : Fragment(), SettingsIdiomPage {
    private val model: ScreenLockSettingsViewModel by viewModels()
    
    private val bindingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.dispatch(ScreenLockContract.Intent.Result(it.data?.getStringExtra(SecurityResultNonce.EXTRA_REQUEST_ID), Stage.AwaitingBinding, it.resultCode == Activity.RESULT_OK))
    }
    private val verificationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.dispatch(ScreenLockContract.Intent.Result(it.data?.getStringExtra(SecurityResultNonce.EXTRA_REQUEST_ID), Stage.AwaitingVerification, it.resultCode == Activity.RESULT_OK))
    }
    private val setupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.dispatch(ScreenLockContract.Intent.Result(it.data?.getStringExtra(SecurityResultNonce.EXTRA_REQUEST_ID), Stage.AwaitingSetup, it.resultCode == Activity.RESULT_OK))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        var visible by mutableStateOf(false)
        (view as ComposeView).setContent {
            val state by model.state.collectAsState()
            DifftTheme(applyWindowBackground = ownsWindow()) {
                ScreenLockSettingsContent(state, containerPadsBottom(), showsDetailBackButton(),
                    onBack = { detailNavigation().pop(this@ScreenLockSettingFragment) },
                    onToggle = { kind, enabled -> model.dispatch(ScreenLockContract.Intent.Toggle(kind, enabled)) },
                    onPath = { model.dispatch(ScreenLockContract.Intent.SetShowPath(it)) },
                    onTimeout = { detailNavigation().push(this@ScreenLockSettingFragment, SetPasscodeTimeoutDestination) },
                )
                if (visible && state.pending?.stage == Stage.ChoosingBinding) {
                    BindingChoiceDialog(
                        onDismiss = { model.dispatch(ScreenLockContract.Intent.DismissBinding) },
                        onChoose = { model.dispatch(ScreenLockContract.Intent.ChooseBinding(it)) },
                    )
                }
                if (visible && state.promptBinding) {
                    AlertDialog(
                        onDismissRequest = { model.dispatch(ScreenLockContract.Intent.DismissPrompt) },
                        title = { Text(stringResource(R.string.settings_bind_account_prompt_title), color = DifftTheme.colors.textPrimary) },
                        text = { Text(stringResource(R.string.settings_bind_account_prompt_message), color = DifftTheme.colors.textSecondary) },
                        containerColor = DifftTheme.colors.bg,
                        confirmButton = { TextButton(onClick = { model.dispatch(ScreenLockContract.Intent.BeginBinding) }) { Text(stringResource(R.string.settings_bind_account_prompt_bind), color = DifftTheme.colors.primary) } },
                        dismissButton = { TextButton(onClick = { model.dispatch(ScreenLockContract.Intent.DismissPrompt) }) { Text(stringResource(R.string.settings_bind_account_prompt_not_now), color = DifftTheme.colors.textSecondary) } },
                    )
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                visible = true
                try {
                    launch { model.state.collect { launchReady() } }
                    launch { model.effect.collect { effect ->
                        when (effect) {
                            is ScreenLockContract.Effect.Disabled -> ToastUtil.show(if (effect.kind == ScreenLockContract.Kind.Pattern) R.string.settings_pattern_disabled_title else R.string.settings_passcode_disabled_title)
                        }
                    } }
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    
                    
                    visible = false
                }
            }
        }
    }

    private fun launchReady() {
        val request = model.takeVisibleLaunch() ?: return
        val intent = when (request.stage) {
            Stage.ReadyForBinding -> Intent(requireContext(), BindAccountActivity::class.java)
                .putExtra(BindAccountActivity.INTENT_EXTRA_TYPE, request.bindingType)
            Stage.ReadyForVerification -> Intent(requireContext(), ScreenLockActivity::class.java)
                .putExtra(ScreenLockActivity.EXTRA_VERIFICATION_MODE, true)
            Stage.ReadyForSetup -> Intent(requireContext(), if (request.kind == ScreenLockContract.Kind.Pattern) SetPatternActivity::class.java else SetPasscodeActivity::class.java)
            else -> return
        }.putExtra(SecurityResultNonce.EXTRA_REQUEST_ID, request.requestId)
        when (request.stage) {
            Stage.ReadyForBinding -> bindingLauncher.launch(intent)
            Stage.ReadyForVerification -> verificationLauncher.launch(intent)
            Stage.ReadyForSetup -> setupLauncher.launch(intent)
            else -> Unit
        }
    }

    override fun onResume() { super.onResume(); model.dispatch(ScreenLockContract.Intent.Refresh) }
}

@Composable
internal fun ScreenLockSettingsContent(
    state: ScreenLockContract.State,
    padsBottom: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onToggle: (ScreenLockContract.Kind, Boolean) -> Unit,
    onPath: (Boolean) -> Unit,
    onTimeout: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(DifftTheme.colors.bg).detailPageInsets(padsBottom)) {
        TitleBar(titleText = stringResource(R.string.settings_screen_lock), onBackClick = onBack, showBackButton = showBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = DifftSettingsDefaults.PageInset)) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup {
                DifftSwitchRow(stringResource(R.string.settings_pattern_lock), state.pattern,
                    { onToggle(ScreenLockContract.Kind.Pattern, it) }, enabled = state.pending == null,
                    modifier = Modifier.background(DifftSettingsDefaults.RowSurfaceColor), contentPadding = DifftSettingsDefaults.RowContentPadding)
                DifftSwitchRow(stringResource(R.string.settings_passcode_lock), state.passcode,
                    { onToggle(ScreenLockContract.Kind.Passcode, it) }, enabled = state.pending == null,
                    modifier = Modifier.background(DifftSettingsDefaults.RowSurfaceColor), contentPadding = DifftSettingsDefaults.RowContentPadding)
            }
            if (state.pattern || state.passcode) {
                Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
                val context = androidx.compose.ui.platform.LocalContext.current
                DifftSettingsGroup { DifftSettingsRow(stringResource(R.string.settings_screen_lock_timeout), value = state.timeout?.let { passcodeTimeoutLabel(context, it) }, onClick = onTimeout) }
            }
            if (state.pattern) {
                Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
                DifftSettingsGroup { DifftSwitchRow(stringResource(R.string.settings_pattern_show_path), state.showPath, onPath,
                    enabled = state.pending == null, modifier = Modifier.background(DifftSettingsDefaults.RowSurfaceColor), contentPadding = DifftSettingsDefaults.RowContentPadding) }
                Text(stringResource(R.string.settings_pattern_show_path_desc), style = DifftTheme.typography.p3, color = DifftTheme.colors.textSecondary)
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}

@Composable
private fun BindingChoiceDialog(onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_bind_account_prompt_title), color = DifftTheme.colors.textPrimary) },
        containerColor = DifftTheme.colors.bg,
        confirmButton = { TextButton(onClick = { onChoose(BindAccountActivity.TYPE_BIND_EMAIL) }) { Text(stringResource(com.difft.android.login.R.string.login_bind_email), color = DifftTheme.colors.primary) } },
        dismissButton = { TextButton(onClick = { onChoose(BindAccountActivity.TYPE_BIND_PHONE) }) { Text(stringResource(com.difft.android.login.R.string.login_bind_phone_number), color = DifftTheme.colors.primary) } },
    )
}
