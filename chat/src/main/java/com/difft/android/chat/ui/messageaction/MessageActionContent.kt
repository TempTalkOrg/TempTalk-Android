package com.difft.android.chat.ui.messageaction

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.difft.android.base.ui.theme.DifftThemePreview
import com.difft.android.chat.R

/**
 * Arrow configuration for popup
 * @param isBelow true if popup is below the anchor (arrow points up), false if above (arrow points down)
 * @param arrowOffsetX horizontal offset of arrow from popup left edge
 */
data class ArrowConfig(
    val isBelow: Boolean,
    val arrowOffsetX: Dp
)

private val ARROW_WIDTH = 14.dp
private val ARROW_HEIGHT = 6.dp
private val PANEL_CORNER = 16.dp
private val PANEL_ELEVATION = 12.dp
private val CELL_HEIGHT = 68.dp
private val GRID_LINE = 1.dp

/** Visual lower bound for a cell width: icon 20dp + breathing room, conservatively 32dp. */
private val MIN_CELL_WIDTH = 32.dp

/** Adaptive column count. n<=4 -> n; 5 -> 5; 6-8 -> 4; 9-10 -> 5 (at most 2 rows). */
internal fun colsForN(n: Int): Int = when {
    n <= 1 -> 1
    n <= 4 -> n
    n == 5 -> 5
    n <= 8 -> 4
    else -> 5
}

/** Design cell width: 4-col (and n<=3 single row) = 68dp; 5-col = 64dp. */
internal fun designCellWidth(cols: Int): Dp = if (cols == 5) 64.dp else 68.dp

/**
 * Width guard (decision #11): panel width = min(design width, maxPanelWidth); shrink cell width
 * (column count unchanged) when over budget. The shrink branch MUST coerceAtLeast(MIN_CELL_WIDTH):
 * maxPanelWidth is only guaranteed >=0 (host computeMaxPanelWidth), not >= gaps, so a degenerate
 * narrow value (e.g. max=0, cols=5, gaps=4 -> (0-4)/5 = -0.8dp) would produce a negative Dp — illegal
 * for Modifier.width(). Clamping converts that degeneration into a legal lower bound (the panel may
 * then overflow the host, an acceptable degradation vs. a negative-width crash / collapsed cell).
 */
internal fun computeCellWidth(cols: Int, maxPanelWidth: Dp): Dp {
    val design = designCellWidth(cols)
    val gaps = GRID_LINE * (cols - 1)
    val designPanel = design * cols + gaps
    return if (designPanel <= maxPanelWidth) design
    else ((maxPanelWidth - gaps) / cols).coerceAtLeast(MIN_CELL_WIDTH)
}

/** Split into rows and pad the last row with null (empty cells) up to [cols]. */
internal fun padRows(actions: List<MessageAction>, cols: Int): List<List<MessageAction?>> =
    actions.chunked(cols).map { row ->
        if (row.size < cols) row + List(cols - row.size) { null } else row
    }

/**
 * Host-side helper (shared by the three direct-consumer hosts): max panel width from a content bound.
 * coerceAtLeast(0) guarantees non-negative; computeCellWidth's clamp handles the near-zero case.
 */
internal fun computeMaxPanelWidth(contentWidthPx: Int, edgePaddingPx: Int, density: Density): Dp =
    with(density) { (contentWidthPx - 2 * edgePaddingPx).coerceAtLeast(0).toDp() }

/**
 * Main content composable for the message action popup.
 *
 * Renders a self-adaptive grid of actions in a forced-dark panel (theme-independent).
 * Shared by all five consumer surfaces; per-surface differences live entirely in the
 * [actions] list, [showReactionBar] and [maxPanelWidth] inputs — no business branching here.
 *
 * @param actions Fully-expanded action list, already ordered by the config layer (Master Order).
 * @param maxPanelWidth Upper bound for panel width (decision #11). No default on purpose: the width
 *        guard silently no-ops if a host forgets to wire it, so every call site must pass it explicitly.
 * @param onReactionClick Called when emoji is clicked. Parameters: (emoji, isRemove).
 * @param arrowConfig Optional arrow configuration. If provided, draws an arrow pointing to anchor.
 */
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
    arrowConfig: ArrowConfig? = null,
    onMeasured: ((Dp, Dp) -> Unit)? = null
) {
    val density = LocalDensity.current
    val panelColor = MessageActionMenuColors.panel

    val cols = colsForN(actions.size)
    val cellWidth = computeCellWidth(cols, maxPanelWidth)
    val panelWidth = cellWidth * cols + GRID_LINE * (cols - 1)

    Column(
        modifier = modifier.onGloballyPositioned { coordinates ->
            onMeasured?.invoke(
                with(density) { coordinates.size.width.toDp() },
                with(density) { coordinates.size.height.toDp() }
            )
        },
        horizontalAlignment = Alignment.Start
    ) {
        // Arrow on top (when popup is below anchor)
        if (arrowConfig != null && arrowConfig.isBelow) {
            PopupArrow(
                isPointingUp = true,
                offsetX = arrowConfig.arrowOffsetX,
                color = panelColor
            )
        }

        Card(
            shape = RoundedCornerShape(PANEL_CORNER),
            colors = CardDefaults.cardColors(containerColor = panelColor),
            elevation = CardDefaults.cardElevation(defaultElevation = PANEL_ELEVATION)
        ) {
            Column(modifier = Modifier.width(panelWidth)) {
                // Reaction bar
                if (showReactionBar && reactions.isNotEmpty()) {
                    ReactionBar(
                        reactions = reactions,
                        selectedEmojis = selectedEmojis,
                        onReactionClick = onReactionClick,
                        onMoreClick = onMoreEmojiClick
                    )
                    HorizontalDivider(thickness = GRID_LINE, color = MessageActionMenuColors.gridLine)
                }

                // Action grid
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

        // Arrow on bottom (when popup is above anchor)
        if (arrowConfig != null && !arrowConfig.isBelow) {
            PopupArrow(
                isPointingUp = false,
                offsetX = arrowConfig.arrowOffsetX,
                color = panelColor
            )
        }
    }
}

/**
 * Arrow indicator for popup
 */
@Composable
private fun PopupArrow(
    isPointingUp: Boolean,
    offsetX: Dp,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .offset(x = offsetX - ARROW_WIDTH / 2)
            .size(width = ARROW_WIDTH, height = ARROW_HEIGHT)
    ) {
        val path = Path().apply {
            if (isPointingUp) {
                // Triangle pointing up: bottom-left -> top-center -> bottom-right
                moveTo(0f, size.height)
                lineTo(size.width / 2, 0f)
                lineTo(size.width, size.height)
                close()
            } else {
                // Triangle pointing down: top-left -> bottom-center -> top-right
                moveTo(0f, 0f)
                lineTo(size.width / 2, size.height)
                lineTo(size.width, 0f)
                close()
            }
        }
        drawPath(path, color)
    }
}

private const val MAX_VISIBLE_EMOJIS = 4
private val REACTION_ITEM_SIZE = 36.dp

/**
 * Reaction bar with emoji buttons.
 * Shows up to 4 emojis + an add-reaction button with [Arrangement.SpaceEvenly] —
 * all gaps equal, including the edge gaps (user-adjudicated over the prototype's
 * padding-24 + SpaceBetween).
 */
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
                onClick = { onReactionClick(emoji, isSelected) }  // isSelected = remove
            )
        }

        // Add-reaction button (opens full emoji picker)
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

/**
 * Single emoji item with optional selected state (forced-dark selected highlight).
 */
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
            .then(if (isSelected) Modifier.background(Color(0x33FFFFFF)) else Modifier)  // 20% white
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 23.sp
        )
    }
}

/**
 * Adaptive action grid: rows of [cols] cells separated by 1dp grid lines, last row padded with
 * empty cells. Uses explicit Column{Row} (not FlowRow) for precise trailing-cell padding and a
 * fixed row height so [VerticalDivider] spans the full cell height.
 */
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
                HorizontalDivider(thickness = GRID_LINE, color = MessageActionMenuColors.gridLine)
            }
            Row(modifier = Modifier.height(CELL_HEIGHT)) {
                row.forEachIndexed { colIndex, action ->
                    if (colIndex > 0) {
                        VerticalDivider(thickness = GRID_LINE, color = MessageActionMenuColors.gridLine)
                    }
                    if (action != null) {
                        ActionCell(
                            action = action,
                            width = cellWidth,
                            onClick = { onActionClick(action) }
                        )
                    } else {
                        // Empty trailing cell keeps the row width aligned with the panel width.
                        Spacer(modifier = Modifier.width(cellWidth).height(CELL_HEIGHT))
                    }
                }
            }
        }
    }
}

/**
 * Single action cell: icon + label vertically centered (decision #10) inside a fixed 68dp-tall,
 * [width]-wide box. Forced-dark tint — [MessageActionMenuColors.danger] for destructive actions.
 */
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

// ============== Previews ==============

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
            selectedEmojis = setOf("👍", "😂"),  // Multiple selected
            onActionClick = {},
            onReactionClick = { _, _ -> },
            onMoreEmojiClick = {},
            maxPanelWidth = PREVIEW_MAX_PANEL_WIDTH_DP.dp,
            arrowConfig = ArrowConfig(isBelow = false, arrowOffsetX = 150.dp)  // Above anchor, arrow points down
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
            arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = 150.dp)  // Below anchor, arrow points up
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
            arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = 100.dp)
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
            arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = 150.dp)
        )
    }
}
