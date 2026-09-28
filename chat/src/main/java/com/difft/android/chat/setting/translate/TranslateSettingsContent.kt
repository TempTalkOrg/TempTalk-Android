package com.difft.android.chat.setting.translate

import android.content.Context
import android.content.res.Configuration
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsOptionRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.setting.translate.mvi.TranslateSettingsContract.State
import com.difft.android.chat.translate.model.FailureReason
import com.difft.android.chat.translate.translateValueDescRes
import com.difft.android.chat.translate.translateValueLabelRes

/** The three auto-translate values, in display order: off, Chinese, English. */
private val TranslateOptions: List<Int> = listOf(0, 1, 2)

/**
 * Stateless body of the conversation auto-translate screen: the three options, the explanation that
 * switches with the selection, and — only while something is wrong — the state of the translation
 * pack the selection needs.
 *
 * Selections are dropped until [State.loaded]: before the stored value is known, the default 0
 * would read as "currently off" and a tap would look like a real change. They are dropped again
 * while [State.pending] so a double tap cannot start two writes.
 */
@Composable
fun TranslateSettingsContent(
    state: State,
    onSelect: (Int) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        val selectable = state.loaded && !state.pending
        DifftSettingsGroup(
            footer = {
                DifftSettingsCaption(
                    text = stringResource(translateValueDescRes(state.selected)),
                    color = DifftTheme.colors.textInfo,
                )
            },
        ) {
            TranslateOptions.forEach { value ->
                DifftSettingsOptionRow(
                    label = stringResource(translateValueLabelRes(value)),
                    selected = state.selected == value,
                    onClick = { if (selectable) onSelect(value) },
                )
            }
        }

        // Model status is informational, so it renders as caption text under the options rather
        // than as settings rows; a ready model says nothing at all. Only the actions the user can
        // actually take (retry / cancel) are tappable.
        val rows = state.models.filter { it.phase != State.Phase.IDLE && it.phase != State.Phase.READY }
        if (state.selected != 0) {
            if (rows.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = DifftSettingsDefaults.CaptionInset)) {
                    Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
                    rows.forEach { row -> ModelStatusCaption(row, onRetry, onCancel) }
                    if (rows.any { it.stuck || it.reason == FailureReason.STUCK_NO_PROGRESS }) {
                        Spacer(Modifier.height(CaptionLineSpacing))
                        DifftSettingsCaption(stringResource(R.string.chat_translate_model_stuck_hint))
                    }
                }
            }
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

/**
 * One model's status as caption text plus its inline actions. A stuck download may still recover
 * on its own, so retry is offered next to cancel rather than forced.
 */
@Composable
private fun ModelStatusCaption(
    row: State.ModelRow,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    when (row.phase) {
        State.Phase.DOWNLOADING -> {
            val label = if (row.stuck) R.string.chat_translate_model_stuck else R.string.chat_translate_model_downloading
            DifftSettingsCaption(listOfNotNull(stringResource(label), progressText(context, row)).joinToString(" "))
            CaptionActions(
                listOfNotNull(
                    if (row.stuck) R.string.chat_translate_retry to onRetry else null,
                    R.string.chat_translate_cancel to onCancel,
                ),
            )
        }

        State.Phase.FAILED -> {
            val reason = row.reason?.let { stringResource(failureReasonRes(it)) }
            DifftSettingsCaption(listOfNotNull(stringResource(R.string.chat_translate_model_failed), reason).joinToString(": "))
            CaptionActions(listOf(R.string.chat_translate_retry to onRetry))
        }

        // Filtered out by the caller.
        State.Phase.IDLE, State.Phase.READY -> Unit
    }
}

/** Tappable action words in the accent colour, laid out on one caption line. */
@Composable
private fun CaptionActions(actions: List<Pair<Int, () -> Unit>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(CaptionActionSpacing)) {
        actions.forEach { (res, onClick) ->
            DifftSettingsCaption(
                text = stringResource(res),
                color = DifftTheme.colors.textInfo,
                modifier = Modifier.clickable(onClick = onClick),
            )
        }
    }
}

private val CaptionLineSpacing = 4.dp
private val CaptionActionSpacing = 16.dp

/** "12 MB/30 MB" while DownloadManager knows the total; null before it does. */
private fun progressText(context: Context, row: State.ModelRow): String? {
    if (row.totalBytes <= 0) return null
    return context.getString(
        R.string.chat_translate_model_progress,
        Formatter.formatShortFileSize(context, row.bytesSoFar),
        Formatter.formatShortFileSize(context, row.totalBytes),
    )
}

@StringRes
private fun failureReasonRes(reason: FailureReason): Int = when (reason) {
    FailureReason.NO_NETWORK -> R.string.chat_translate_reason_no_network
    FailureReason.NO_SPACE -> R.string.chat_translate_reason_no_space
    FailureReason.DM_DISABLED -> R.string.chat_translate_reason_dm_disabled
    FailureReason.SDK_ERROR -> R.string.chat_translate_reason_sdk_error
    FailureReason.STUCK_NO_PROGRESS -> R.string.chat_translate_model_stuck
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TranslateSettingsContentPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 520.dp)) {
            TranslateSettingsContent(
                state = State(
                    loaded = true,
                    selected = 1,
                    models = listOf(
                        State.ModelRow(
                            modelCode = "zh",
                            phase = State.Phase.DOWNLOADING,
                            bytesSoFar = 12_300_000,
                            totalBytes = 30_000_000,
                        ),
                    ),
                ),
                onSelect = {},
                onRetry = {},
                onCancel = {},
            )
        }
    }
}
