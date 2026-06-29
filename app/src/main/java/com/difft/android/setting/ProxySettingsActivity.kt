package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.widget.ToastUtil
import com.difft.android.setting.viewmodel.ProxySettingsViewModel
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.ProbeState
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
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var showPassphraseDialog by remember { mutableStateOf(false) }
    var passphraseError by remember { mutableStateOf(false) }

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

    ProxySettingsContent(
        viewModel = viewModel,
        state = state,
        topInset = topInset,
        onBack = onBack,
    )

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
private fun ProxySettingsContent(
    viewModel: ProxySettingsViewModel,
    state: ProxySettingsViewModel.UiState,
    topInset: Dp,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = DifftTheme.colors.backgroundTertiary)
            .verticalScroll(rememberScrollState())
            .imePadding(),
    ) {
        Spacer(modifier = Modifier.height(topInset))

        ProxyTopBar(onBack = onBack)

        if (state.readOnly) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.proxy_call_in_progress_hint),
                fontSize = 13.sp,
                color = DifftTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        ProxyUseProxyCard(
            // During a call the switch is NOT greyed out: it shows the real state and
            // a tap raises a toast (handled in the ViewModel) instead of toggling.
            checked = state.useProxy,
            onCheckedChange = { viewModel.onUseProxyChange(it) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Indented to 32dp so the description aligns with the card's inner title
        // ("Use proxy") rather than the card's outer edge, matching the Figma spec
        // (312dp text centered within the 344dp card → 16dp screen + 16dp inset).
        ProxyUseProxyDescription(modifier = Modifier.padding(horizontal = 32.dp))

        Spacer(modifier = Modifier.height(24.dp))

        ProxyProtectCallCard(
            checked = state.protectCallIp,
            // Operable only while the proxy is ON: greyed out (but still tappable to
            // surface a toast) when the proxy is off, matching the Figma spec and the
            // ViewModel's onProtectCallIpChange gating.
            enabledLook = state.useProxy,
            onCheckedChange = { viewModel.onProtectCallIpChange(it) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        ProxyProtectCallDescription(modifier = Modifier.padding(horizontal = 32.dp))

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.proxy_address_label),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
            color = DifftTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = state.address,
            onValueChange = { viewModel.onAddressChange(it) },
            readOnly = state.readOnly,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(8.dp),
            singleLine = false,
            maxLines = 3,
            placeholder = { Text(text = stringResource(R.string.proxy_address_hint)) },
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = DifftTheme.colors.textPrimary,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DifftTheme.colors.background,
                unfocusedContainerColor = DifftTheme.colors.background,
                focusedBorderColor = DifftTheme.colors.line,
                unfocusedBorderColor = DifftTheme.colors.line,
            ),
        )

        if (state.showNoTurnWarning) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.proxy_no_turn_warning),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = DifftTheme.colors.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (state.probe != ProbeState.None) {
            Spacer(modifier = Modifier.height(10.dp))
            ProxyStatusSection(
                probe = state.probe,
                recheckEnabled = !state.readOnly,
                onRecheck = { viewModel.checkConnectivity() },
            )
        }

        // Placed right below the status area (not pinned to the bottom) so the soft
        // keyboard can't cover it while editing the proxy address.
        Spacer(modifier = Modifier.height(24.dp))

        ProxySaveButton(
            enabled = state.hasChanges && !state.isSaving && !state.readOnly,
            onClick = { viewModel.save() },
        )

        Spacer(
            modifier = Modifier
                .navigationBarsPadding()
                .height(16.dp),
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
            tint = DifftTheme.colors.textPrimary,
            modifier = Modifier
                .size(24.dp)
                .clickable { onBack() },
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.proxy_settings_title),
                style = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    color = DifftTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
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
                color = DifftTheme.colors.background,
                shape = RoundedCornerShape(8.dp),
            )
            .heightIn(min = 52.dp)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.proxy_use_proxy),
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
            color = DifftTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DifftTheme.colors.textOnPrimary,
                checkedTrackColor = DifftTheme.colors.primary,
                uncheckedThumbColor = DifftTheme.colors.textOnPrimary,
                uncheckedTrackColor = DifftTheme.colors.icon,
            ),
        )
    }
}

/**
 * Feature description with a trailing "Learn more" link that opens the proxy help
 * page in the external browser. Rendered below the "Use proxy" card.
 */
@Composable
private fun ProxyUseProxyDescription(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val linkColor = DifftTheme.colors.primary
    val text = buildAnnotatedString {
        append(stringResource(R.string.proxy_use_proxy_desc))
        append(" ")
        withLink(
            LinkAnnotation.Clickable(
                tag = "proxy_help",
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            ) {
                context.openExternalBrowser(PROXY_HELP_URL)
            },
        ) {
            append(stringResource(R.string.proxy_learn_more))
        }
    }
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        color = DifftTheme.colors.textTertiary,
        modifier = modifier,
    )
}

/**
 * "Protect IP address in calls" toggle card. Mirrors [ProxyUseProxyCard] but the
 * switch is greyed (via [enabledLook]) while the proxy is OFF. The switch stays
 * tappable in that state so the ViewModel can toast "enable the proxy first"
 * instead of silently swallowing the tap.
 */
@Composable
private fun ProxyProtectCallCard(
    checked: Boolean,
    enabledLook: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                color = DifftTheme.colors.background,
                shape = RoundedCornerShape(8.dp),
            )
            .heightIn(min = 52.dp)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.proxy_protect_call),
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
            color = DifftTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = if (enabledLook) {
                SwitchDefaults.colors(
                    checkedThumbColor = DifftTheme.colors.textOnPrimary,
                    checkedTrackColor = DifftTheme.colors.primary,
                    uncheckedThumbColor = DifftTheme.colors.textOnPrimary,
                    uncheckedTrackColor = DifftTheme.colors.icon,
                )
            } else {
                // Greyed look while the proxy is off (switch remains tappable).
                SwitchDefaults.colors(
                    checkedThumbColor = DifftTheme.colors.textOnPrimary,
                    checkedTrackColor = DifftTheme.colors.backgroundDisabled,
                    uncheckedThumbColor = DifftTheme.colors.textOnPrimary,
                    uncheckedTrackColor = DifftTheme.colors.backgroundDisabled,
                )
            },
        )
    }
}

/** Caption under the "Protect IP address in calls" card (Figma §16822:19386). */
@Composable
private fun ProxyProtectCallDescription(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.proxy_protect_call_desc),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        color = DifftTheme.colors.textTertiary,
        modifier = modifier,
    )
}

private const val PROXY_HELP_URL = "https://quicall.app/proxy-help.html"

/** Shared min height for the status row so its height stays stable across states. */
private val STATUS_ROW_MIN_HEIGHT = 40.dp

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
            .padding(horizontal = 16.dp)
            .height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DifftTheme.colors.primary,
            disabledContainerColor = DifftTheme.colors.backgroundDisabled,
            contentColor = DifftTheme.colors.textOnPrimary,
            disabledContentColor = DifftTheme.colors.textDisabled,
        ),
    ) {
        Text(
            text = stringResource(R.string.proxy_save),
            fontSize = 16.sp,
        )
    }
}

/**
 * The single status area (proxy design §6): renders exactly ONE main status.
 *
 * - [ProbeState.Checking] → a small loading indicator only (no text, no icon, §6.0).
 * - a settled main status → a green/red dot + colored label. A compact "recheck"
 *   refresh icon is pinned to the trailing edge ONLY for failure states; a
 *   successful status shows no retry affordance.
 */
@Composable
private fun ProxyStatusSection(
    probe: ProbeState,
    recheckEnabled: Boolean,
    onRecheck: () -> Unit,
) {
    if (probe == ProbeState.Checking) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = STATUS_ROW_MIN_HEIGHT)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = DifftTheme.colors.primary,
            )
        }
        return
    }
    if (!probe.isMain) return

    val textColor = if (probe.isSuccess) {
        DifftTheme.colors.success
    } else {
        DifftTheme.colors.error
    }

    // A fixed min height keeps the status area from collapsing/expanding when it
    // cycles failure → Checking → failure on recheck (the recheck IconButton makes
    // the settled row taller than the lone spinner), which otherwise visibly jumps.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = STATUS_ROW_MIN_HEIGHT)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = textColor, shape = CircleShape),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = stringResource(mainStatusTextRes(probe)),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        // The recheck affordance only appears when the connection test FAILED;
        // a successful status needs no retry.
        if (!probe.isSuccess) {
            Spacer(modifier = Modifier.size(8.dp))
            RecheckIconButton(enabled = recheckEnabled, onClick = onRecheck)
        }
    }
}

/** Maps a settled main [ProbeState] to its status text resource (§6.1–§6.3). */
private fun mainStatusTextRes(probe: ProbeState): Int = when (probe) {
    ProbeState.ProxyAvailable -> R.string.proxy_status_available
    ProbeState.ServiceReachable -> R.string.proxy_e2e_ok
    ProbeState.ServiceUnreachable -> R.string.proxy_e2e_failed
    is ProbeState.ProxyUnavailable ->
        if (probe.verifyFailed) R.string.proxy_check_fail_verify else R.string.proxy_status_unavailable
    // None / Checking are filtered out before this is called.
    else -> R.string.proxy_status_unavailable
}

/**
 * The "recheck" affordance (§6): a compact, icon-only refresh button — deliberately
 * low-emphasis (neutral tint, never red). Triggers a fresh probe of the saved address.
 * The 40dp [IconButton] keeps an adequate touch target while the glyph stays small.
 */
@Composable
private fun RecheckIconButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_proxy_refresh),
            contentDescription = stringResource(R.string.proxy_recheck),
            tint = DifftTheme.colors.icon,
            modifier = Modifier.size(20.dp),
        )
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
                    color = DifftTheme.colors.textSecondary,
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
                        color = DifftTheme.colors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotBlank() && !isLoading,
                colors = ButtonDefaults.textButtonColors(contentColor = DifftTheme.colors.textInfo),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(text = stringResource(R.string.proxy_passphrase_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
                colors = ButtonDefaults.textButtonColors(contentColor = DifftTheme.colors.textInfo),
            ) {
                Text(text = stringResource(R.string.proxy_passphrase_cancel))
            }
        },
    )
}
