package com.difft.android.linkeddevices

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.difft.android.R
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.DifftScreen
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.base.widget.getSafeActivity
import com.difft.android.chat.invite.ScanActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import java.text.DateFormat
import java.util.Date

/** Long-press menu state: the press point (root-local px) + the target device. */
private data class MenuState(val position: Offset, val device: DeviceUiState)

/**
 * Shared list screen for both hosts. The phone Activity passes showBackButton=true; the tablet
 * Fragment passes showBackButton=!dualPane. The root [Box] hosts the in-composition long-press
 * overlay menu. Fetch is driven by the host's onResume, not from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(
    viewModel: LinkedDevicesViewModel,
    showBackButton: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var menu by remember { mutableStateOf<MenuState?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                LinkedDevicesViewModel.UiEvent.UnlinkFailed ->
                    ToastUtil.show(R.string.linked_devices_unlink_failed)
                LinkedDevicesViewModel.UiEvent.FetchFailed ->
                    ToastUtil.show(R.string.linked_devices_list_update_failed)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // BaseActivity skips auto system-bar padding for Compose roots; the screen owns it.
            .systemBarsPadding()
            .onSizeChanged { rootSize = it }
            .onGloballyPositioned { rootCoords = it },
    ) {
        DifftScreen(
            topBar = {
                TitleBar(
                    titleText = stringResource(R.string.linked_devices_title),
                    titleEndText = if (state.devices.isNotEmpty()) "(${state.devices.size})" else "",
                    showBackButton = showBackButton,
                    onBackClick = onBack,
                )
            },
        ) { padding ->
            // Full-screen spinner only before the first successful load; any later state (incl. a
            // fetch failure) renders the list body, so the Link New Device entry never disappears.
            // Fetch failures surface as a one-shot toast, never a full-screen error page.
            if (state.isLoading && !state.hasLoadedOnce) {
                LoadingBody(padding)
            } else {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.pullRefresh() },
                    modifier = Modifier.padding(padding),
                ) {
                    DeviceListBody(
                        devices = state.devices,
                        rootCoords = rootCoords,
                        onLongPress = { device, rootPos -> menu = MenuState(rootPos, device) },
                        onLinkNew = {
                            viewModel.onLinkNewDeviceClicked()
                            context.getSafeActivity()?.let { ScanActivity.startActivity(it) }
                        },
                    )
                }
            }
        }

        menu?.let { m ->
            LinkedDeviceUnlinkMenu(
                position = m.position,
                rootSize = rootSize,
                onDismiss = { menu = null },
                onUnlink = {
                    menu = null
                    showUnlinkConfirm(context, m.device, viewModel)
                },
            )
        }
    }
}

@Composable
private fun LoadingBody(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = DifftTheme.colors.primary)
    }
}

@Composable
private fun DeviceListBody(
    devices: List<DeviceUiState>,
    rootCoords: LayoutCoordinates?,
    onLongPress: (DeviceUiState, Offset) -> Unit,
    onLinkNew: () -> Unit,
) {
    // Rounded section cards on the bg page, bg-colored dividers. An empty list shows only the
    // Link New Device card (no caption). Single DateFormat shared by every row.
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(DifftTheme.spacing.insetLarge),
    ) {
        if (devices.isNotEmpty()) {
            item {
                SectionCard {
                    devices.forEachIndexed { index, device ->
                        DeviceRow(device, dateFormat, rootCoords, onLongPress)
                        if (index < devices.lastIndex) {
                            HorizontalDivider(
                                color = DifftTheme.colors.bg,
                                thickness = DifftTheme.spacing.dividerThickness,
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(DifftTheme.spacing.insetLarge)) }
        }
        item { SectionCard { LinkNewDeviceRow(onClick = onLinkNew) } }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DifftTheme.colors.bgElevated),
    ) {
        content()
    }
}

@Composable
private fun DeviceRow(
    device: DeviceUiState,
    dateFormat: DateFormat,
    rootCoords: LayoutCoordinates?,
    onLongPress: (DeviceUiState, Offset) -> Unit,
) {
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Read the latest coords/callback inside the long-press gesture: pointerInput is keyed on
    // device.id and would otherwise capture a stale (first-frame null) rootCoords forever.
    val currentRootCoords by rememberUpdatedState(rootCoords)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val name = device.displayName ?: stringResource(R.string.linked_devices_unnamed)
    val createdText = remember(device.created) { dateFormat.format(Date(device.created)) }
    val lastActiveText = remember(device.lastActive) { dateFormat.format(Date(device.lastActive)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowCoords = it }
            .pointerInput(device.id) {
                detectTapGestures(
                    onLongPress = { local ->
                        val root = currentRootCoords
                        val row = rowCoords
                        if (root != null && row != null) {
                            currentOnLongPress(device, root.localPositionOf(row, local))
                        }
                    },
                )
            }
            .padding(horizontal = DifftTheme.spacing.insetLarge, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = name,
            style = DifftTheme.typography.bodyLarge,
            color = DifftTheme.colors.textPrimary,
        )
        Text(
            text = stringResource(R.string.linked_devices_linked_at, createdText),
            style = DifftTheme.typography.bodyMedium,
            color = DifftTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(R.string.linked_devices_last_active, lastActiveText),
            style = DifftTheme.typography.bodyMedium,
            color = DifftTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun LinkNewDeviceRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = DifftTheme.spacing.insetLarge, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.linked_devices_link_new_title),
                style = DifftTheme.typography.bodyLarge,
                color = DifftTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.linked_devices_link_new_subtitle),
                style = DifftTheme.typography.bodyMedium,
                color = DifftTheme.colors.textSecondary,
            )
        }
        Icon(
            painter = painterResource(com.difft.android.chat.R.drawable.chat_ic_arrow_right),
            contentDescription = null,
            tint = DifftTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Confirm dialog. Resolves the display name + destructive button colour via resources and delegates
 * to the app-wide [ComposeDialogManager]. Colour token is base R.color.error.
 */
private fun showUnlinkConfirm(context: Context, device: DeviceUiState, viewModel: LinkedDevicesViewModel) {
    val name = device.displayName ?: context.getString(R.string.linked_devices_unnamed)
    ComposeDialogManager.showMessageDialog(
        context = context,
        title = context.getString(R.string.linked_devices_unlink_confirm_title, name),
        message = context.getString(R.string.linked_devices_unlink_confirm_body),
        confirmText = context.getString(R.string.linked_devices_unlink_action),
        cancelText = context.getString(R.string.linked_devices_unlink_cancel),
        confirmButtonColor = Color(ContextCompat.getColor(context, com.difft.android.base.R.color.error)),
        onConfirm = { viewModel.unlink(device.id) },
    )
}
