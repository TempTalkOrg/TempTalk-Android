package com.difft.android.chat.ui.messageaction

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme
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

private val ARROW_WIDTH = 12.dp
private val ARROW_HEIGHT = 6.dp

/**
 * Main content composable for the message action popup
 * 
 * @param onReactionClick Called when emoji is clicked. Parameters: (emoji, isRemove)
 *        - isRemove=true means user wants to remove their existing reaction
 *        - isRemove=false means user wants to add a new reaction
 * @param arrowConfig Optional arrow configuration. If provided, draws an arrow pointing to anchor
 */
@Composable
fun MessageActionContent(
    reactions: List<String>,
    selectedEmojis: Set<String>,
    showReactionBar: Boolean,
    quickActions: List<MessageAction>,
    onReactionClick: (String, Boolean) -> Unit,
    onMoreEmojiClick: () -> Unit,
    onActionClick: (MessageAction) -> Unit,
    modifier: Modifier = Modifier,
    arrowConfig: ArrowConfig? = null,
    onMeasured: ((Dp, Dp) -> Unit)? = null
) {
    val density = LocalDensity.current
    val backgroundColor = DifftTheme.colors.backgroundActionPopup
    
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
                color = backgroundColor
            )
        }
        
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor
            ),
            elevation = CardDefaults.elevatedCardElevation(
                defaultElevation = 4.dp
            )
        ) {
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Min)
                    .padding(8.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Reaction bar
                if (showReactionBar && reactions.isNotEmpty()) {
                    ReactionBar(
                        reactions = reactions,
                        selectedEmojis = selectedEmojis,
                        onReactionClick = onReactionClick,
                        onMoreClick = onMoreEmojiClick
                    )
                    
                    HorizontalDivider(
                        color = DifftTheme.colors.textDisabled,
                        thickness = 0.5.dp
                    )
                }
                
                // Quick action bar
                if (quickActions.isNotEmpty()) {
                    QuickActionBar(
                        actions = quickActions,
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
                color = backgroundColor
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

// Item dimensions
private val ITEM_WIDTH = 55.dp
private val ITEM_SPACING = 6.dp

private const val MAX_VISIBLE_EMOJIS = 4

/**
 * Reaction bar with emoji buttons
 * Shows up to 4 emojis + more button = 5 items (aligned with quick actions)
 */
@Composable
private fun ReactionBar(
    reactions: List<String>,
    selectedEmojis: Set<String>,
    onReactionClick: (String, Boolean) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Show at most 4 emojis + more button
    val displayedEmojis = reactions.take(MAX_VISIBLE_EMOJIS)
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
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
        
        // More emoji button
        Column(
            modifier = Modifier
                .width(ITEM_WIDTH)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onMoreClick() }
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_emoji_more),
                contentDescription = "More emojis",
                tint = DifftTheme.colors.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Single emoji item with optional selected state
 */
@Composable
private fun EmojiItem(
    emoji: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedBackground = if (DifftTheme.colors.isDark) {
        Color(0x33FFFFFF)  // 20% white for dark mode
    } else {
        Color(0x18000000)  // 10% black for light mode
    }
    
    Column(
        modifier = modifier
            .width(ITEM_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(35.dp)
                .then(
                    if (isSelected) {
                        Modifier
                            .clip(CircleShape)
                            .background(selectedBackground)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                fontSize = 24.sp
            )
        }
    }
}

/**
 * Quick action bar with action buttons
 */
@Composable
private fun QuickActionBar(
    actions: List<MessageAction>,
    onActionClick: (MessageAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
        verticalAlignment = Alignment.Top
    ) {
        actions.forEach { action ->
            QuickActionItem(
                action = action,
                onClick = { onActionClick(action) }
            )
        }
    }
}

/**
 * Single quick action item
 */
@Composable
private fun QuickActionItem(
    action: MessageAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconTint = if (action.isDestructive) {
        DifftTheme.colors.error
    } else {
        DifftTheme.colors.textPrimary
    }
    
    val textColor = if (action.isDestructive) {
        DifftTheme.colors.error
    } else {
        DifftTheme.colors.textPrimary
    }
    
    Column(
        modifier = modifier
            .width(ITEM_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(action.labelRes),
            fontSize = 11.sp,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 14.sp
        )
    }
}

// ============== Previews ==============

@Preview(showBackground = true, name = "With Arrow Down")
@Composable
private fun MessageActionContentPreview() {
    DifftThemePreview {
        MessageActionContent(
            reactions = listOf("👍", "👌", "😂", "🙏"),
            selectedEmojis = setOf("👍", "😂"),  // Multiple selected
            showReactionBar = true,
            quickActions = listOf(
                MessageAction.quote(),
                MessageAction.copy(),
                MessageAction.translate(),
                MessageAction.forward(),
                MessageAction.more()
            ),
            arrowConfig = ArrowConfig(isBelow = false, arrowOffsetX = 150.dp),  // Above anchor, arrow points down
            onReactionClick = { _, _ -> },
            onMoreEmojiClick = {},
            onActionClick = {}
        )
    }
}

@Preview(showBackground = true, name = "With Arrow Up")
@Composable
private fun MessageActionContentArrowUpPreview() {
    DifftThemePreview {
        MessageActionContent(
            reactions = listOf("👍", "👌", "😂", "🙏"),
            selectedEmojis = setOf("👍"),
            showReactionBar = true,
            quickActions = listOf(
                MessageAction.quote(),
                MessageAction.copy(),
                MessageAction.translate(),
                MessageAction.forward(),
                MessageAction.more()
            ),
            arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = 150.dp),  // Below anchor, arrow points up
            onReactionClick = { _, _ -> },
            onMoreEmojiClick = {},
            onActionClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageActionContentNoReactionsPreview() {
    DifftThemePreview {
        MessageActionContent(
            reactions = emptyList(),
            selectedEmojis = emptySet(),
            showReactionBar = false,
            quickActions = listOf(
                MessageAction.quote(),
                MessageAction.copy(),
                MessageAction.forward(),
                MessageAction.more()
            ),
            arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = 100.dp),
            onReactionClick = { _, _ -> },
            onMoreEmojiClick = {},
            onActionClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Dark Theme")
@Composable
private fun MessageActionContentDarkPreview() {
    DifftThemePreview(darkTheme = true) {
        MessageActionContent(
            reactions = listOf("👍", "👌", "😂", "🙏"),
            selectedEmojis = setOf("😂"),
            showReactionBar = true,
            quickActions = listOf(
                MessageAction.quote(),
                MessageAction.copy(),
                MessageAction.translate(),
                MessageAction.forward(),
                MessageAction.more()
            ),
            arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = 150.dp),
            onReactionClick = { _, _ -> },
            onMoreEmojiClick = {},
            onActionClick = {}
        )
    }
}
