package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.difft.android.base.ui.compose.floatingMenuEntrance
import com.difft.android.base.utils.dp as dpToPx


private const val MEASURE_OFFSET_PX = -9999















@Composable
internal fun MessageActionPopupOverlay(
    bubbleBounds: PopupRect,
    contentBounds: PopupRect,
    selectionRows: SelectionRowRects,
    isOutgoing: Boolean,
    config: MessageActionConfigBuilder.Config,
    menuMode: MessageActionPopup.MenuMode,
    selectionActions: List<MessageAction>,
    onMenuBoundsChanged: (Rect?) -> Unit,
    onPlaced: (placement: PopupPlacement, popupHeight: Int) -> Unit,
    onReactionClick: (emoji: String, isRemove: Boolean) -> Unit,
    onMoreEmojiClick: () -> Unit,
    onActionClick: (MessageAction) -> Unit,
    onSelectionActionClick: (MessageAction) -> Unit,
) {
    val density = LocalDensity.current
    val metrics = remember { PopupMetrics(edgePaddingPx = 8.dpToPx, arrowGapPx = 2.dpToPx, arrowMinInsetPx = 16.dpToPx) }

    
    
    val maxPanelWidth = remember(contentBounds.width) {
        computeMaxPanelWidth(contentBounds.width, metrics.edgePaddingPx, density)
    }
    
    
    var popupWidth by remember(menuMode, maxPanelWidth) { mutableIntStateOf(0) }
    var popupHeight by remember { mutableIntStateOf(0) }
    val anchorInput = remember(bubbleBounds, selectionRows) {
        SelectionAnchorInput(bubbleBounds, selectionRows.start?.toPopupRect(), selectionRows.end?.toPopupRect())
    }

    val isFullMode = menuMode == MessageActionPopup.MenuMode.FULL
    val reactions = if (isFullMode) config.reactions else emptyList()
    val selectedEmojis = if (isFullMode) config.selectedEmojis else emptySet()
    val showReactionBar = isFullMode && config.showReactionBar
    val quickActions = if (isFullMode) config.quickActions else selectionActions
    val actionClickHandler: (MessageAction) -> Unit = if (isFullMode) onActionClick else onSelectionActionClick

    
    Box(modifier = Modifier.fillMaxSize()) {
        if (popupWidth == 0 || popupHeight == 0) {
            SideEffect { onMenuBoundsChanged(null) }
            Box(
                modifier = Modifier
                    .offset { IntOffset(MEASURE_OFFSET_PX, MEASURE_OFFSET_PX) }
                    .onGloballyPositioned { coordinates ->
                        if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                            popupWidth = coordinates.size.width
                            popupHeight = coordinates.size.height
                        }
                    }
            ) {
                MessageActionContent(
                    actions = quickActions,
                    showReactionBar = showReactionBar,
                    reactions = reactions,
                    selectedEmojis = selectedEmojis,
                    maxPanelWidth = maxPanelWidth,
                    onReactionClick = { _, _ -> },
                    onMoreEmojiClick = { },
                    onActionClick = { }
                )
            }
        } else {
            val placement = remember(anchorInput, contentBounds, popupWidth, popupHeight, isOutgoing) {
                MessageActionPopupPositioner.place(anchorInput, contentBounds, popupWidth, popupHeight, isOutgoing, metrics)
            }
            LaunchedEffect(placement.anchor.mode, placement.placementMode, menuMode) {
                onPlaced(placement, popupHeight)
            }
            
            val origin = TransformOrigin(
                pivotFractionX = (placement.arrowOffsetXPx.toFloat() / popupWidth).coerceIn(0f, 1f),
                pivotFractionY = if (placement.isBelow) 0f else 1f,
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset(placement.x, placement.y) }
                    .onGloballyPositioned { onMenuBoundsChanged(it.boundsInRoot().toAndroidRect()) }
                    .floatingMenuEntrance(origin)
            ) {
                MessageActionContent(
                    actions = quickActions,
                    showReactionBar = showReactionBar,
                    reactions = reactions,
                    selectedEmojis = selectedEmojis,
                    maxPanelWidth = maxPanelWidth,
                    onReactionClick = onReactionClick,
                    onMoreEmojiClick = onMoreEmojiClick,
                    onActionClick = actionClickHandler
                )
            }
        }
    }
}
