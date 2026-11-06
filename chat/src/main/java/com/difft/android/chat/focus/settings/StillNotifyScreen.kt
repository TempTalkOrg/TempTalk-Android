package com.difft.android.chat.focus.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSwitchRow

/** Which of the three items still notify; shared by "Other Groups Still Notify" and "Still Notify". */
data class StillNotifyState(
    val mentionMe: Boolean = true,
    val mentionAll: Boolean = false,
    val groupCall: Boolean = false,
) {
    fun isOn(item: StillNotifyItem): Boolean = when (item) {
        StillNotifyItem.MENTION_ME -> mentionMe
        StillNotifyItem.MENTION_ALL -> mentionAll
        StillNotifyItem.GROUP_CALL -> groupCall
    }

    fun with(item: StillNotifyItem, on: Boolean): StillNotifyState = when (item) {
        StillNotifyItem.MENTION_ME -> copy(mentionMe = on)
        StillNotifyItem.MENTION_ALL -> copy(mentionAll = on)
        StillNotifyItem.GROUP_CALL -> copy(groupCall = on)
    }
}

/** Three switches (@Me / @All / Group calls) on a settings page; each change is reported at once. */
@Composable
fun StillNotifyScreen(
    title: String,
    state: StillNotifyState,
    onToggle: (StillNotifyItem, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TitleBar(titleText = title, onBackClick = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup {
                StillNotifyItem.entries.forEach { item ->
                    DifftSwitchRow(
                        label = stringResource(item.labelRes()),
                        checked = state.isOn(item),
                        onCheckedChange = { onToggle(item, it) },
                        modifier = Modifier.fillMaxWidth().background(DifftSettingsDefaults.RowSurfaceColor),
                        contentPadding = DifftSettingsDefaults.RowContentPadding,
                    )
                }
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}
