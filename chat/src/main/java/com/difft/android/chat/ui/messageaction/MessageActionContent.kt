package com.difft.android.chat.ui.messageaction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.compose.floatingSurface
import com.difft.android.base.ui.theme.DifftRadius
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.DifftThemePreview
import com.difft.android.chat.R


private val PANEL_CORNER = DifftRadius.floating
private val CELL_HEIGHT = 68.dp

private val GRID_LINE = 1.dp


private val MIN_CELL_WIDTH = 32.dp


internal fun colsForN(n: Int): Int = when {
    n <= 1 -> 1
    n <= 4 -> n
    n == 5 -> 5
    n <= 8 -> 4
    else -> 5
}


internal fun designCellWidth(cols: Int): Dp = if (cols == 5) 64.dp else 68.dp









internal fun computeCellWidth(cols: Int, maxPanelWidth: Dp, gridLine: Dp = GRID_LINE): Dp {
    val design = designCellWidth(cols)
    val gaps = gridLine * (cols - 1)
    val designPanel = design * cols + gaps
    return if (designPanel <= maxPanelWidth) design
    else ((maxPanelWidth - gaps) / cols).coerceAtLeast(MIN_CELL_WIDTH)
}


internal fun padRows(actions: List<MessageAction>, cols: Int): List<List<MessageAction?>> =
    actions.chunked(cols).map { row ->
        if (row.size < cols) row + List(cols - row.size) { null } else row
    }





internal fun computeMaxPanelWidth(contentWidthPx: Int, edgePaddingPx: Int, density: Density): Dp =
    with(density) { (contentWidthPx - 2 * edgePaddingPx).coerceAtLeast(0).toDp() }













@Composable
fun MessageActionContent(
    actions: List<MessageAction>,
    showReactionBar: Boolean,
    reactions: List<String>,
    selectedEmojis: Set<String>,
    onActionClick: (MessageAction) -> Unit,
    onReactionClick: (String, Boolean) -> Unit,
    onMoreEmojiClick: () -> Unit,
    maxPanelWidth: Dp,
    modifier: Modifier = Modifier,
    onMeasured: ((Dp, Dp) -> Unit)? = null
) {
    val density = LocalDensity.current

    val cols = colsForN(actions.size)
    val gridLine = DifftTheme.hairline
    val cellWidth = computeCellWidth(cols, maxPanelWidth, gridLine)
    val panelWidth = cellWidth * cols + gridLine * (cols - 1)

    Column(
        modifier = modifier.onGloballyPositioned { coordinates ->
            onMeasured?.invoke(
                with(density) { coordinates.size.width.toDp() },
                with(density) { coordinates.size.height.toDp() }
            )
        },
        horizontalAlignment = Alignment.Start
    ) {
        Column(modifier = Modifier.floatingSurface(PANEL_CORNER)) {
            Column(modifier = Modifier.width(panelWidth)) {
                
                if (showReactionBar && reactions.isNotEmpty()) {
                    ReactionBar(
                        reactions = reactions,
                        selectedEmojis = selectedEmojis,
                        onReactionClick = onReactionClick,
                        onMoreClick = onMoreEmojiClick
                    )
                    HorizontalDivider(thickness = DifftTheme.hairline, color = MessageActionMenuColors.gridLine)
                }

                
                if (actions.isNotEmpty()) {
                    ActionGrid(
                        actions = actions,
                        cols = cols,
                        cellWidth = cellWidth,
                        onActionClick = onActionClick
                    )
                }
            }
        }

    }
}

private const val MAX_VISIBLE_EMOJIS = 4
private val REACTION_ITEM_SIZE = 36.dp







@Composable
private fun ReactionBar(
    reactions: List<String>,
    selectedEmojis: Set<String>,
    onReactionClick: (String, Boolean) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayedEmojis = reactions.take(MAX_VISIBLE_EMOJIS)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayedEmojis.forEach { emoji ->
            val isSelected = emoji in selectedEmojis
            EmojiItem(
                emoji = emoji,
                isSelected = isSelected,
                onClick = { onReactionClick(emoji, isSelected) }  
            )
        }

        
        Box(
            modifier = Modifier
                .size(REACTION_ITEM_SIZE)
                .clip(CircleShape)
                .clickable { onMoreClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.chat_message_action_add_reaction),
                contentDescription = "More emojis",
                tint = MessageActionMenuColors.reactionAddIcon,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}




@Composable
private fun EmojiItem(
    emoji: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(REACTION_ITEM_SIZE)
            .clip(CircleShape)
            .then(if (isSelected) Modifier.background(MessageActionMenuColors.pressedPill) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 23.sp
        )
    }
}






@Composable
private fun ActionGrid(
    actions: List<MessageAction>,
    cols: Int,
    cellWidth: Dp,
    onActionClick: (MessageAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = padRows(actions, cols)
    Column(modifier = modifier) {
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                HorizontalDivider(thickness = DifftTheme.hairline, color = MessageActionMenuColors.gridLine)
            }
            Row(modifier = Modifier.height(CELL_HEIGHT)) {
                row.forEachIndexed { colIndex, action ->
                    if (colIndex > 0) {
                        VerticalDivider(thickness = DifftTheme.hairline, color = MessageActionMenuColors.gridLine)
                    }
                    if (action != null) {
                        ActionCell(
                            action = action,
                            width = cellWidth,
                            onClick = { onActionClick(action) }
                        )
                    } else {
                        
                        Spacer(modifier = Modifier.width(cellWidth).height(CELL_HEIGHT))
                    }
                }
            }
        }
    }
}





@Composable
private fun ActionCell(
    action: MessageAction,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (action.isDestructive) {
        MessageActionMenuColors.danger
    } else {
        MessageActionMenuColors.contentDefault
    }

    Column(
        modifier = modifier
            .width(width)
            .height(CELL_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(action.labelRes),
            fontSize = 11.sp,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp
        )
    }
}



private const val PREVIEW_MAX_PANEL_WIDTH_DP = 360

@Preview(showBackground = true, name = "Grid 7 items, arrow down")
@Composable
private fun MessageActionContentPreview() {
    DifftThemePreview {
        MessageActionContent(
            actions = listOf(
                MessageAction.quote(),
                MessageAction.copy(),
                MessageAction.forward(),
                MessageAction.multiSelect(),
                MessageAction.translate(),
                MessageAction.saveToNote(),
                MessageAction.recall()
            ),
            showReactionBar = true,
            reactions = listOf("👍", "👌", "😂", "🙏"),
            selectedEmojis = setOf("👍", "😂"),  
            onActionClick = {},
            onReactionClick = { _, _ -> },
            onMoreEmojiClick = {},
            maxPanelWidth = PREVIEW_MAX_PANEL_WIDTH_DP.dp,
        )
    }
}

@Preview(showBackground = true, name = "Grid 5 items, arrow up")
@Composable
private fun MessageActionContentArrowUpPreview() {
    DifftThemePreview {
        MessageActionContent(
            actions = listOf(
                MessageAction.quote(),
                MessageAction.copy(),
                MessageAction.forward(),
                MessageAction.multiSelect(),
                MessageAction.recall()
            ),
            showReactionBar = true,
            reactions = listOf("👍", "👌", "😂", "🙏"),
            selectedEmojis = setOf("👍"),
            onActionClick = {},
            onReactionClick = { _, _ -> },
            onMoreEmojiClick = {},
            maxPanelWidth = PREVIEW_MAX_PANEL_WIDTH_DP.dp,
        )
    }
}

@Preview(showBackground = true, name = "No reactions (failed-state style)")
@Composable
private fun MessageActionContentNoReactionsPreview() {
    DifftThemePreview {
        MessageActionContent(
            actions = listOf(
                MessageAction.resend(),
                MessageAction.delete(),
                MessageAction.moreInfo()
            ),
            showReactionBar = false,
            reactions = emptyList(),
            selectedEmojis = emptySet(),
            onActionClick = {},
            onReactionClick = { _, _ -> },
            onMoreEmojiClick = {},
            maxPanelWidth = PREVIEW_MAX_PANEL_WIDTH_DP.dp,
        )
    }
}

@Preview(showBackground = true, name = "Grid 10 items (5-col full)")
@Composable
private fun MessageActionContentFullGridPreview() {
    DifftThemePreview(darkTheme = true) {
        MessageActionContent(
            actions = listOf(
                MessageAction.quote(),
                MessageAction.copy(),
                MessageAction.forward(),
                MessageAction.multiSelect(),
                MessageAction.translate(),
                MessageAction.save(isMediaFile = true),
                MessageAction.favoriteGif(),
                MessageAction.saveToNote(),
                MessageAction.recall(),
                MessageAction.moreInfo()
            ),
            showReactionBar = true,
            reactions = listOf("👍", "👌", "😂", "🙏"),
            selectedEmojis = setOf("😂"),
            onActionClick = {},
            onReactionClick = { _, _ -> },
            onMoreEmojiClick = {},
            maxPanelWidth = PREVIEW_MAX_PANEL_WIDTH_DP.dp,
        )
    }
}
