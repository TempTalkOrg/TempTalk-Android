package com.difft.android.chat.common.compose

import androidx.compose.foundation.layout.Spacer
import com.difft.android.base.ui.sidechrome.SideChromeModel
import com.difft.android.base.ui.sidechrome.RailAction
import com.difft.android.base.ui.sidechrome.PublishSideChrome
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.difft.android.base.ui.compose.FloatingIconButton
import com.difft.android.base.ui.compose.FloatingIconButtonEdge
import com.difft.android.base.ui.compose.FloatingIconButtonTitleGap
import com.difft.android.base.ui.compose.FloatingTextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme





sealed interface TitleBarAction {
    
    data class Icon(@DrawableRes val iconRes: Int, val label: String = "", val onClick: () -> Unit) : TitleBarAction

    



    data class Text(val label: String, @DrawableRes val sideBarIcon: Int? = null, val onClick: () -> Unit) : TitleBarAction
}






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
    val barAction = when (action) {
        is TitleBarAction.Icon -> RailAction(id = "action", icon = action.iconRes, contentDescription = action.label, onClick = action.onClick)
        is TitleBarAction.Text -> action.sideBarIcon?.let { RailAction(id = "action", icon = it, contentDescription = action.label, onClick = action.onClick) }
        null -> null
    }
    
    val onSideBar = PublishSideChrome(SideChromeModel(back = onBack, actions = listOfNotNull(barAction)))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(background),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onSideBar) {
            Spacer(modifier = Modifier.width(SIDE_BAR_TITLE_INSET))
        } else {
            FloatingIconButton(
                iconRes = com.difft.android.base.R.drawable.chative_ic_back,
                contentDescription = null,
                onClick = onBack,
                modifier = Modifier.padding(start = FloatingIconButtonEdge, end = FloatingIconButtonTitleGap),
            )
        }

        
        
        
        
        Box(modifier = Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = collapsed,
                enter = fadeIn(tween(ANIM_MS)) + slideInVertically(tween(ANIM_MS)) { slideOffset },
                exit = fadeOut(tween(ANIM_MS)) + slideOutVertically(tween(ANIM_MS)) { slideOffset },
            ) {
                Text(
                    text = title,
                    style = DifftTheme.typography.h5,
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
            when (val shown = action.takeUnless { onSideBar && barAction != null }) {
                is TitleBarAction.Icon -> FloatingIconButton(
                    iconRes = shown.iconRes,
                    contentDescription = null,
                    onClick = shown.onClick,
                    modifier = Modifier.padding(end = FloatingIconButtonEdge),
                )

                is TitleBarAction.Text -> FloatingTextButton(
                    text = shown.label,
                    onClick = shown.onClick,
                    style = DifftTheme.typography.p2,
                    color = DifftTheme.colors.icon,
                    modifier = Modifier.padding(end = FloatingIconButtonEdge),
                )

                null -> Unit
            }
        }
    }
}

private const val ANIM_MS = 160


private val SIDE_BAR_TITLE_INSET = 16.dp
private val BAR_HEIGHT = 52.dp
private val ACTION_MIN_WIDTH = 48.dp
private val TITLE_SLIDE = 8.dp
