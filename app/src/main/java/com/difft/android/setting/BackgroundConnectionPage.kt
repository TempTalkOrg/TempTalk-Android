package com.difft.android.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsRow
import com.difft.android.base.ui.compose.settings.DifftSwitchRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.PackageUtil
import com.difft.android.chat.R as ChatR

internal const val TAG_BACKGROUND_SWITCH = "background_connection_switch"

internal fun bgRowTag(row: BgRow): String = "background_row_${row.name}"


internal data class BackgroundConnectionPageState(
    val isBackgroundConnectionEnabled: Boolean,
    val brand: OemBrand,
    val rows: List<BgRow>,
    val check: BgCheckState,
    val highlightedRow: BgRow? = null,
    val scrollKey: Int = 0,
)






@Composable
internal fun BackgroundConnectionPage(
    state: BackgroundConnectionPageState,
    onBackClick: () -> Unit,
    onSwitchChanged: (Boolean) -> Unit,
    onRowClick: (BgRow) -> Unit,
    onGuideClick: () -> Unit,
) {
    val generic = state.rows.filter { it in GENERIC_ROWS }
    val vendor = state.rows.filter { it !in GENERIC_ROWS }
    val appName = remember { PackageUtil.getAppName().orEmpty() }
    Column(Modifier.fillMaxSize()) {
        TitleBar(
            titleText = stringResource(ChatR.string.background_connection),
            onBackClick = onBackClick,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup {
                DifftSwitchRow(
                    label = stringResource(ChatR.string.background_connection),
                    checked = state.isBackgroundConnectionEnabled,
                    onCheckedChange = onSwitchChanged,
                    contentPadding = DifftSettingsDefaults.RowContentPadding,
                    modifier = Modifier.testTag(TAG_BACKGROUND_SWITCH),
                )
            }

            if (generic.isNotEmpty()) {
                Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
                DifftSettingsGroup(title = stringResource(ChatR.string.background_system_checks_title)) {
                    for (row in generic) CheckRow(row, state, onRowClick)
                }
            }

            if (vendor.isNotEmpty()) {
                Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
                
                DifftSettingsGroup(
                    title = stringResource(ChatR.string.background_vendor_checks_title),
                    footer = { DifftSettingsCaption(stringResource(ChatR.string.background_vendor_checks_caption)) },
                ) {
                    for (row in vendor) {
                        val note = hintFor(row, state.brand)?.let { if (it.takesAppName) stringResource(it.res, appName) else stringResource(it.res) }
                        CheckRow(row, state, onRowClick, note)
                    }
                }
            }

            SetupGuideFooter(
                onClick = onGuideClick,
                prompt = ChatR.string.background_connection_guide_prompt_page,
                link = ChatR.string.background_connection_guide_link_page,
            )
        }
    }
}






@Composable
private fun CheckRow(row: BgRow, state: BackgroundConnectionPageState, onRowClick: (BgRow) -> Unit, note: String? = null) {
    val status = statusOf(row, state.check)
    val highlighted = row == state.highlightedRow
    
    val onClick: (() -> Unit)? =
        if (row == BgRow.BATTERY_OPTIMIZATION && state.check.isIgnoringBatteryOpt) null else { { onRowClick(row) } }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted, state.scrollKey) {
        if (highlighted) requester.bringIntoView()
    }
    DifftSettingsRow(
        
        modifier = Modifier.bringIntoViewRequester(requester).semantics(mergeDescendants = true) {}.testTag(bgRowTag(row)),
        onClick = onClick,
        
        trailing = if (onClick == null) ({ Spacer(Modifier.size(DifftSettingsDefaults.ChevronSize)) }) else null,
    ) {
        if (note == null) {
            CheckRowLine(row, status, highlighted)
        } else {
            
            Column(Modifier.weight(1f)) {
                CheckRowLabel(row, highlighted, Modifier)
                Text(text = note, style = DifftSettingsDefaults.CaptionTextStyle, color = DifftTheme.colors.textTertiary)
            }
            CheckRowStatus(status)
        }
    }
}

@Composable
private fun RowScope.CheckRowLine(row: BgRow, status: BgStatus, highlighted: Boolean) {
    CheckRowLabel(row, highlighted, Modifier.weight(1f))
    CheckRowStatus(status)
}

@Composable
private fun CheckRowLabel(row: BgRow, highlighted: Boolean, modifier: Modifier) {
    Text(
        text = stringResource(titleFor(row)),
        style = DifftSettingsDefaults.LabelTextStyle,
        color = if (highlighted) DifftTheme.colors.textInfo else DifftTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun CheckRowStatus(status: BgStatus) {
    status.res?.let {
        Spacer(Modifier.width(DifftSettingsDefaults.TrailingGap))
        Text(
            text = stringResource(it),
            style = DifftSettingsDefaults.LabelTextStyle,
            color = if (status.warning) DifftTheme.colors.textWarning else DifftTheme.colors.textTertiary,
            maxLines = 1,
        )
    }
}


@Preview(showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun BackgroundConnectionPagePreview() {
    var switchState by remember { mutableStateOf(false) }
    val brand = OemBrand.VIVO
    DifftTheme {
        BackgroundConnectionPage(
            state = BackgroundConnectionPageState(
                isBackgroundConnectionEnabled = switchState,
                brand = brand,
                rows = backgroundRowOrder(
                    brand,
                    BgRowFlags(
                        showBackgroundRestriction = true,
                        canOpenAutoStart = true,
                        canOpenBackgroundActivity = true,
                        exactAlarmApplicable = false,
                    ),
                ),
                check = BgCheckState(isBackgroundRestricted = false, isIgnoringBatteryOpt = false, isDataSaverRestricted = false),
                highlightedRow = BgRow.BATTERY_OPTIMIZATION,
            ),
            onBackClick = {},
            onSwitchChanged = { switchState = it },
            onRowClick = {},
            onGuideClick = {},
        )
    }
}
