package com.difft.android.call.ui.actionbar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.compose.DifftModalBottomSheet
import com.difft.android.call.BuildConfig
import kotlinx.coroutines.launch

/** Scrim behind the Emoji sheet — same as the More sheet's. */
private val EMOJI_SHEET_SCRIM = Color(0x66000000)


/** Vertical rhythm of the sheet: grab bar → emoji row → divider → chips. */
private val SHEET_ROW_GAP = 16.dp

/**
 * Bottom sheet with one row of quick emoji and the quick-phrase chips. Picking anything sends
 * it and closes the sheet; tapping the scrim just closes it.
 *
 * Data (emoji / phrase presets) comes from the call config exactly as before — only the
 * presentation changed from a floating card to a sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EmojiReactionSheet(
    visible: Boolean,
    emojis: List<String>,
    phrases: List<String>,
    isLandscape: Boolean,
    onEmoji: (String) -> Unit,
    onPhrase: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    // One send per opening: the content stays hit-testable through the hide animation, so a
    // second tap in that window must be a no-op rather than a duplicate reaction.
    var sent by remember { mutableStateOf(false) }
    val pick: (() -> Unit) -> Unit = { send ->
        if (!sent) {
            sent = true
            send()
            dismiss()
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            sent = false
            sheetState.show()
        } else {
            sheetState.hide()
        }
    }

    if (!visible && !sheetState.isVisible) return

    DifftModalBottomSheet(
        sheetState = sheetState,
        scrimColor = EMOJI_SHEET_SCRIM,
        contentWindowInsets = { WindowInsets.navigationBars },
        hideNavigationBar = true,
        onDismissRequest = dismiss,
    ) {
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = BuildConfig.DEBUG }
                .testTag("call_emoji_sheet")
                .fillMaxWidth()
                // Design shows 12dp here and between rows; 16dp was chosen on device because the
                // 28sp emoji row read as crowding the grab bar and the divider at 12.
                .padding(top = SHEET_ROW_GAP, bottom = if (isLandscape) 20.dp else 34.dp),
            verticalArrangement = Arrangement.spacedBy(SHEET_ROW_GAP),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                emojis.forEach { emoji ->
                    Text(
                        text = emoji,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontFamily = FontFamily.Default),
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { pick { onEmoji(emoji) } },
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                thickness = 1.dp,
                color = DifftTheme.colors.line,
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                phrases.forEach { phrase ->
                    PhraseChip(text = phrase) { pick { onPhrase(phrase) } }
                }
            }
        }
    }
}

@Composable
private fun PhraseChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .background(color = DifftTheme.colors.backgroundTertiary, shape = RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            maxLines = 1,
            style = TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight(400),
                color = DifftTheme.colors.textPrimary,
            ),
        )
    }
}
