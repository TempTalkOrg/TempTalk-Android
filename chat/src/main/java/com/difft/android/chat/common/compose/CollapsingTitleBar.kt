package com.difft.android.chat.common.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme

/** Right-hand slot of [CollapsingTitleBar]. Icon and Text share one slot so swapping keeps layout stable. */
sealed interface TitleBarAction {
    data class Icon(@DrawableRes val iconRes: Int, val onClick: () -> Unit) : TitleBarAction
    data class Text(val label: String, val onClick: () -> Unit) : TitleBarAction
}

/**
 * Settings-page top bar that shows no title until the identity header scrolls out of view.
 * When [collapsed] the name fades/slides in (160ms, 8dp) and the bar background dissolves into a
 * top-to-bottom gradient instead of drawing a divider, so content scrolls "under" it.
 */
@Composable
fun CollapsingTitleBar(
    title: String,
    collapsed: Boolean,
    onBack: () -> Unit,
    action: TitleBarAction?,
    modifier: Modifier = Modifier,
) {
    val page = DifftTheme.colors.bg
    val background = if (collapsed) {
        Brush.verticalGradient(listOf(page, page.copy(alpha = 0f)))
    } else {
        Brush.verticalGradient(listOf(page, page))
    }
    val slideOffset = with(LocalDensity.current) { TITLE_SLIDE.roundToPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(background),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(com.difft.android.base.R.drawable.chative_ic_back),
            contentDescription = null,
            modifier = Modifier
                .clickable { onBack() }
                .padding(ICON_PADDING)
                .size(DifftTheme.spacing.iconMedium),
            tint = DifftTheme.colors.textPrimary
        )

        // The Box keeps the weighted slot alive while the title is hidden; AnimatedVisibility drops
        // its own layout node once fully exited, which would let the action slot slide left.
        // Fully qualified: inside the Box the RowScope overload is unreachable (DslMarker), so a plain
        // import makes the call ambiguous.
        Box(modifier = Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = collapsed,
                enter = fadeIn(tween(ANIM_MS)) + slideInVertically(tween(ANIM_MS)) { slideOffset },
                exit = fadeOut(tween(ANIM_MS)) + slideOutVertically(tween(ANIM_MS)) { slideOffset },
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = DifftTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = ACTION_MIN_WIDTH),
            contentAlignment = Alignment.Center
        ) {
            when (action) {
                is TitleBarAction.Icon -> Icon(
                    painter = painterResource(action.iconRes),
                    contentDescription = null,
                    modifier = Modifier
                        .clickable { action.onClick() }
                        .padding(ICON_PADDING)
                        .size(DifftTheme.spacing.iconMedium),
                    tint = DifftTheme.colors.icon
                )

                is TitleBarAction.Text -> Text(
                    text = action.label,
                    style = actionTextStyle,
                    color = DifftTheme.colors.textInfo,
                    modifier = Modifier
                        .clickable { action.onClick() }
                        .padding(horizontal = ICON_PADDING, vertical = ACTION_TEXT_VERTICAL_PADDING)
                )

                null -> Unit
            }
        }
    }
}

private val titleStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
private val actionTextStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)

private const val ANIM_MS = 160
private val BAR_HEIGHT = 52.dp
private val ICON_PADDING = 12.dp
private val ACTION_MIN_WIDTH = 48.dp
private val ACTION_TEXT_VERTICAL_PADDING = 8.dp
private val TITLE_SLIDE = 8.dp
