package com.difft.android.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSwitchRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.ProbeState
import com.difft.android.setting.viewmodel.ProxySettingsViewModel.UiState

private const val PROXY_HELP_URL = "https://quicall.app/proxy-help.html"





@Composable
fun ProxySettingsContent(
    state: UiState,
    onUseProxyChange: (Boolean) -> Unit,
    onProtectCallIpChange: (Boolean) -> Unit,
    onAddressChange: (String) -> Unit,
    onRecheck: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    
    
    
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            ProxySettingsBody(
                state = state,
                onUseProxyChange = onUseProxyChange,
                onProtectCallIpChange = onProtectCallIpChange,
                onAddressChange = onAddressChange,
                onRecheck = onRecheck,
            )
        }
        Spacer(Modifier.height(FOOTER_GAP))
        DifftButton(
            text = stringResource(R.string.proxy_save),
            onClick = onSave,
            enabled = state.hasChanges && !state.isSaving && !state.readOnly,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        )
        Spacer(
            Modifier
                .navigationBarsPadding()
                .height(DifftSettingsDefaults.FirstGroupSpacing),
        )
    }
}

@Composable
private fun ProxySettingsBody(
    state: UiState,
    onUseProxyChange: (Boolean) -> Unit,
    onProtectCallIpChange: (Boolean) -> Unit,
    onAddressChange: (String) -> Unit,
    onRecheck: () -> Unit,
) {
    if (state.readOnly) {
        Spacer(Modifier.height(12.dp))
        DifftSettingsCaption(
            text = stringResource(R.string.proxy_call_in_progress_hint),
            color = DifftTheme.colors.textSecondary,
        )
    }

    Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))

    DifftSettingsGroup(footer = { ProxyUseProxyDescription() }) {
        ProxyGroupSwitchRow(
            
            
            label = stringResource(R.string.proxy_use_proxy),
            checked = state.useProxy,
            onCheckedChange = onUseProxyChange,
        )
    }

    Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))

    DifftSettingsGroup(
        footer = { DifftSettingsCaption(stringResource(R.string.proxy_protect_call_desc)) },
    ) {
        ProxyGroupSwitchRow(
            label = stringResource(R.string.proxy_protect_call),
            checked = state.protectCallIp,
            onCheckedChange = onProtectCallIpChange,
            
            
            switchAppearsEnabled = state.useProxy,
        )
    }

    Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))

    ProxyAddressField(
        address = state.address,
        readOnly = state.readOnly,
        showNoTurnWarning = state.showNoTurnWarning,
        onAddressChange = onAddressChange,
    )

    if (state.probe != ProbeState.None) {
        Spacer(Modifier.height(STATUS_LINE_GAP))
        ProxyStatusLine(
            probe = state.probe,
            recheckEnabled = !state.readOnly,
            onRecheck = onRecheck,
        )
    }

    Spacer(Modifier.height(DifftSettingsDefaults.PageInset))
}


@Composable
private fun ProxyGroupSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchAppearsEnabled: Boolean = true,
) = DifftSwitchRow(
    label = label,
    checked = checked,
    onCheckedChange = onCheckedChange,
    switchAppearsEnabled = switchAppearsEnabled,
    modifier = Modifier
        .fillMaxWidth()
        .background(DifftSettingsDefaults.RowSurfaceColor),
    contentPadding = DifftSettingsDefaults.RowContentPadding,
)





@Composable
private fun ProxyUseProxyDescription() {
    val context = LocalContext.current
    val linkColor = DifftTheme.colors.textInfo
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
        style = DifftSettingsDefaults.CaptionTextStyle,
        color = DifftTheme.colors.textTertiary,
    )
}


@Composable
private fun ProxyAddressField(
    address: String,
    readOnly: Boolean,
    showNoTurnWarning: Boolean,
    onAddressChange: (String) -> Unit,
) {
    DifftSettingsCaption(
        text = stringResource(R.string.proxy_address_label),
        color = DifftTheme.colors.textSecondary,
        style = DifftSettingsDefaults.TitleTextStyle,
    )
    Spacer(Modifier.height(10.dp))

    DifftClearableTextField(
        value = address,
        onValueChange = onAddressChange,
        onClear = { onAddressChange("") },
        hint = stringResource(R.string.proxy_address_hint),
        
        
        clearMode = ClearMode.Never,
        
        readOnly = readOnly,
        
        
        maxLines = Int.MAX_VALUE,
        
        containerColor = DifftTheme.colors.background,
        textStyle = DifftTheme.typography.bodyMedium,
        height = null,
        minHeight = ADDRESS_MIN_HEIGHT,
        contentPadding = PaddingValues(10.dp),
        modifier = Modifier.fillMaxWidth(),
    )

    if (showNoTurnWarning) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.proxy_no_turn_warning),
            style = DifftTheme.typography.bodyMedium,
            color = DifftTheme.colors.textError,
        )
    }
}





@Composable
private fun ProxyStatusLine(
    probe: ProbeState,
    recheckEnabled: Boolean,
    onRecheck: () -> Unit,
) {
    Row(
        
        
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = STATUS_LINE_MIN_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val statusColor = when {
            probe == ProbeState.Checking -> DifftTheme.colors.textPrimary
            probe.isSuccess -> DifftTheme.colors.success
            else -> DifftTheme.colors.textError
        }
        Box(Modifier.size(STATUS_INDICATOR_SLOT), contentAlignment = Alignment.Center) {
            if (probe == ProbeState.Checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = DifftTheme.colors.primary,
                )
            } else {
                Box(Modifier.size(STATUS_DOT_SIZE).background(statusColor, CircleShape))
            }
        }
        if (probe != ProbeState.Checking) {
            Spacer(Modifier.width(STATUS_LINE_GAP))
            Text(
                text = stringResource(mainStatusTextRes(probe)),
                style = DifftTheme.typography.bodyMedium,
                color = statusColor,
                modifier = Modifier.weight(1f),
            )
            
            if (!probe.isSuccess) {
                Spacer(Modifier.width(STATUS_LINE_GAP))
                RecheckIconButton(enabled = recheckEnabled, onClick = onRecheck)
            }
        }
    }
}


private fun mainStatusTextRes(probe: ProbeState): Int = when (probe) {
    ProbeState.ProxyAvailable -> R.string.proxy_status_available
    ProbeState.ServiceReachable -> R.string.proxy_e2e_ok
    ProbeState.ServiceUnreachable -> R.string.proxy_e2e_failed
    is ProbeState.ProxyUnavailable ->
        if (probe.verifyFailed) R.string.proxy_check_fail_verify else R.string.proxy_status_unavailable
    
    else -> R.string.proxy_status_unavailable
}






@Composable
private fun RecheckIconButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            painter = painterResource(com.difft.android.base.R.drawable.base_ic_refresh),
            contentDescription = stringResource(R.string.proxy_recheck),
            tint = DifftTheme.colors.icon,
            modifier = Modifier.size(20.dp),
        )
    }
}


private val ADDRESS_MIN_HEIGHT = 100.dp
private val FOOTER_GAP = 8.dp
private val STATUS_LINE_GAP = 8.dp
private val STATUS_INDICATOR_SLOT = 16.dp
private val STATUS_DOT_SIZE = 10.dp

private val STATUS_LINE_MIN_HEIGHT = 40.dp
