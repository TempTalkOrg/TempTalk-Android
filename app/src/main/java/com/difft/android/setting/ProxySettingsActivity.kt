package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ToastUtil
import com.difft.android.network.proxy.ProxyConnectivityChecker
import com.difft.android.setting.viewmodel.ProxySettingsViewModel
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.ConnStatus
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.E2eStatus
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.SaveResult
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
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var showPassphraseDialog by remember { mutableStateOf(false) }
    var passphraseError by remember { mutableStateOf(false) }

    fun handleResult(result: SaveResult) {
        when (result) {
            SaveResult.Saved -> {
                showPassphraseDialog = false
                onToast(R.string.proxy_saved)
            }

            SaveResult.Invalid -> {
                showPassphraseDialog = false
                onToast(R.string.proxy_invalid_address)
            }

            SaveResult.NeedPassphrase -> {
                passphraseError = false
                showPassphraseDialog = true
            }

            // Keep the dialog open and surface the error so the user can retry.
            SaveResult.WrongPassphrase -> passphraseError = true
        }
    }

    // Save runs off the main thread (PBKDF2 decrypt); outcomes arrive here.
    LaunchedEffect(Unit) {
        viewModel.saveResults.collect { handleResult(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = colorResource(com.difft.android.base.R.color.bg1)),
    ) {
        Spacer(modifier = Modifier.height(topInset))

        ProxyTopBar(onBack = onBack)

        Spacer(modifier = Modifier.height(12.dp))

        ProxyUseProxyCard(
            checked = state.useProxy,
            onCheckedChange = { viewModel.onUseProxyChange(it) },
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.proxy_address_label),
            fontSize = 14.sp,
            color = colorResource(com.difft.android.base.R.color.primary),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.address,
            onValueChange = { viewModel.onAddressChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(8.dp),
            singleLine = false,
            maxLines = 3,
            placeholder = { Text(text = stringResource(R.string.proxy_address_hint)) },
            textStyle = TextStyle(
                fontSize = 15.sp,
                color = colorResource(com.difft.android.base.R.color.t_primary),
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colorResource(com.difft.android.base.R.color.line),
                unfocusedBorderColor = colorResource(com.difft.android.base.R.color.line),
            ),
        )

        if (state.showNoTurnWarning) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.proxy_no_turn_warning),
                fontSize = 13.sp,
                color = colorResource(com.difft.android.base.R.color.error),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (state.connStatus != ConnStatus.NONE) {
            Spacer(modifier = Modifier.height(12.dp))
            ProxyStatusRow(
                status = state.connStatus,
                failure = state.connFailure,
                onRecheck = { viewModel.checkConnectivity() },
            )
        }

        // Stage 2 (end-to-end) row. Visible while stage 1 is AVAILABLE, OR while
        // stage 1 is re-checking (CHECKING) but stage 2 already has a prior status
        // (retry case) — so the row doesn't flicker out mid-recheck.
        val showE2eRow = state.connStatus == ConnStatus.AVAILABLE ||
            (state.connStatus == ConnStatus.CHECKING && state.e2eStatus != E2eStatus.NONE)
        if (showE2eRow) {
            Spacer(modifier = Modifier.height(8.dp))
            ProxyE2eRow(
                e2eStatus = state.e2eStatus,
                onRecheck = { viewModel.checkConnectivity() },
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        ProxySaveButton(
            enabled = state.hasChanges && !state.isSaving,
            onClick = { viewModel.save() },
        )
    }

    if (showPassphraseDialog) {
        PassphraseDialog(
            isError = passphraseError,
            isLoading = state.isSaving,
            onConfirm = { passphrase -> viewModel.saveWithPassphrase(passphrase) },
            onDismiss = { if (!state.isSaving) showPassphraseDialog = false },
        )
    }
}

@Composable
private fun ProxyTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = com.difft.android.chat.R.drawable.chat_contact_detail_ic_back),
            contentDescription = "Back",
            tint = colorResource(com.difft.android.base.R.color.t_primary),
            modifier = Modifier
                .size(24.dp)
                .clickable { onBack() },
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.proxy_settings_title),
                style = TextStyle(
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight(600),
                    color = colorResource(com.difft.android.base.R.color.t_primary),
                ),
            )
        }
        Spacer(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ProxyUseProxyCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                color = colorResource(com.difft.android.base.R.color.bg_elevated),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.proxy_use_proxy),
                fontSize = 16.sp,
                color = colorResource(com.difft.android.base.R.color.t_primary),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.proxy_use_proxy_desc),
                fontSize = 13.sp,
                color = colorResource(com.difft.android.base.R.color.t_secondary),
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colorResource(id = com.difft.android.base.R.color.t_white),
                checkedTrackColor = colorResource(id = com.difft.android.base.R.color.primary),
                uncheckedThumbColor = colorResource(id = com.difft.android.base.R.color.t_white),
                uncheckedTrackColor = colorResource(id = com.difft.android.base.R.color.gray_600),
            ),
        )
    }
}

@Composable
private fun ProxySaveButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(com.difft.android.base.R.color.primary),
            disabledContainerColor = colorResource(com.difft.android.base.R.color.t_disable),
            contentColor = colorResource(com.difft.android.base.R.color.t_white),
            disabledContentColor = colorResource(com.difft.android.base.R.color.t_white),
        ),
    ) {
        Text(
            text = stringResource(R.string.proxy_save),
            fontSize = 16.sp,
            color = colorResource(com.difft.android.base.R.color.t_white),
        )
    }
}

/**
 * Shows the reachability of the saved proxy: a spinner while probing, then a
 * green / red dot with a label. When unreachable, a localized reason is shown
 * and the row is tappable to retry the probe.
 */
@Composable
private fun ProxyStatusRow(
    status: ConnStatus,
    failure: ProxyConnectivityChecker.Failure,
    onRecheck: () -> Unit,
) {
    val checking = status == ConnStatus.CHECKING
    val available = status == ConnStatus.AVAILABLE
    val dotColor = if (available) {
        colorResource(com.difft.android.base.R.color.success)
    } else {
        colorResource(com.difft.android.base.R.color.error)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !checking) { onRecheck() }
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = colorResource(com.difft.android.base.R.color.primary),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color = dotColor, shape = CircleShape),
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(
                    when (status) {
                        ConnStatus.CHECKING -> R.string.proxy_status_checking
                        ConnStatus.AVAILABLE -> R.string.proxy_status_available
                        else -> R.string.proxy_status_unavailable
                    }
                ),
                fontSize = 14.sp,
                color = colorResource(com.difft.android.base.R.color.t_primary),
            )
            if (status == ConnStatus.UNAVAILABLE) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.proxy_recheck),
                    fontSize = 13.sp,
                    color = colorResource(com.difft.android.base.R.color.primary),
                )
            }
        }
        if (status == ConnStatus.UNAVAILABLE) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    when (failure) {
                        ProxyConnectivityChecker.Failure.UNREACHABLE -> R.string.proxy_check_fail_unreachable
                        ProxyConnectivityChecker.Failure.TIMEOUT -> R.string.proxy_check_fail_timeout
                        ProxyConnectivityChecker.Failure.PIN_MISMATCH -> R.string.proxy_check_fail_pin
                        else -> R.string.proxy_check_fail_unknown
                    }
                ),
                fontSize = 13.sp,
                color = colorResource(com.difft.android.base.R.color.t_secondary),
            )
        }
    }
}

/**
 * Stage 2 row: whether TempTalk is reachable *through* the proxy. Reuses the
 * stage-1 visual language (spinner / green dot / red dot + label). Only the
 * FAILED state is tappable to retry; CHECKING blocks re-entry.
 *
 * Content matrix (§7.5): CHECKING → spinner; OK → green dot; FAILED → red dot +
 * "retry"; NONE → renders nothing (transient AVAILABLE+NONE fallback when the
 * proxy was disabled mid-probe).
 */
@Composable
private fun ProxyE2eRow(
    e2eStatus: E2eStatus,
    onRecheck: () -> Unit,
) {
    if (e2eStatus == E2eStatus.NONE) return // AVAILABLE+NONE fallback: nothing to show.

    val checking = e2eStatus == E2eStatus.CHECKING
    val ok = e2eStatus == E2eStatus.OK
    val dotColor = if (ok) {
        colorResource(com.difft.android.base.R.color.success)
    } else {
        colorResource(com.difft.android.base.R.color.error)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = e2eStatus != E2eStatus.CHECKING) { onRecheck() }
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = colorResource(com.difft.android.base.R.color.primary),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color = dotColor, shape = CircleShape),
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(
                    when (e2eStatus) {
                        E2eStatus.CHECKING -> R.string.proxy_e2e_checking
                        E2eStatus.OK -> R.string.proxy_e2e_ok
                        E2eStatus.FAILED, E2eStatus.NONE -> R.string.proxy_e2e_failed
                    }
                ),
                fontSize = 14.sp,
                color = colorResource(com.difft.android.base.R.color.t_primary),
            )
            if (e2eStatus == E2eStatus.FAILED) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.proxy_recheck),
                    fontSize = 13.sp,
                    color = colorResource(com.difft.android.base.R.color.primary),
                )
            }
        }
    }
}

/** Collects the out-of-band passphrase used to decrypt an encrypted proxy link. */
@Composable
private fun PassphraseDialog(
    isError: Boolean,
    isLoading: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(text = stringResource(R.string.proxy_passphrase_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.proxy_passphrase_desc),
                    fontSize = 13.sp,
                    color = colorResource(com.difft.android.base.R.color.t_secondary),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    singleLine = true,
                    isError = isError,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text(text = stringResource(R.string.proxy_passphrase_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isError) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.proxy_passphrase_wrong),
                        fontSize = 12.sp,
                        color = colorResource(com.difft.android.base.R.color.error),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotBlank() && !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(text = stringResource(R.string.proxy_passphrase_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(text = stringResource(R.string.proxy_passphrase_cancel))
            }
        },
    )
}
