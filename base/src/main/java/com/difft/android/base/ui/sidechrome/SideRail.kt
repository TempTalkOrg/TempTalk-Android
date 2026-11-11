package com.difft.android.base.ui.sidechrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.compose.floatingPill
import com.difft.android.base.ui.theme.DifftTheme

private val RailWidth = 84.dp
private val ButtonSize = 48.dp
private val RailPadding = 24.dp

private val RailPaddingStart = 12.dp

private class RailItem(
    val key: String,
    val icon: Int,
    val contentDescription: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val badge: Int = 0,
    val tinted: Boolean = true,
)









@Composable
internal fun SideRail(
    model: SideChromeModel?,
    lower: (@Composable () -> Unit)? = null,
    topClearancePx: Int = 0,
    onButtonBounds: (key: String, boundsInWindow: Rect) -> Unit = { _, _ -> },
) {
    val backLabel = stringResource(R.string.side_chrome_back)
    val items = buildList {
        model?.back?.let { add(RailItem("back", model.backIcon.takeIf { it != 0 } ?: R.drawable.chative_ic_back, backLabel, it)) }
        model?.actions?.forEach { action ->
            add(RailItem(action.id, action.icon, action.contentDescription, action.onClick, action.enabled && !action.loading, action.loading, action.badge, action.tinted))
        }
    }
    Column(
        modifier = Modifier
            .width(RailWidth)
            .fillMaxHeight()
            
            
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets(top = topClearancePx))
                    .only(WindowInsetsSides.Vertical),
            )
            .padding(start = RailPaddingStart, top = RailPadding, end = RailPadding, bottom = RailPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (items.isNotEmpty()) {
            Column(modifier = Modifier.floatingPill(glass = true), verticalArrangement = Arrangement.Top) {
                items.forEach { item -> RailButton(item, onBounds = { onButtonBounds(item.key, it) }) }
            }
        }
        if (lower != null) {
            Spacer(modifier = Modifier.weight(1f))
            lower()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailButton(item: RailItem, onBounds: (Rect) -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Left),
        tooltip = { PlainTooltip { Text(item.contentDescription) } },
        state = rememberTooltipState(),
    ) {
        Box(contentAlignment = Alignment.TopEnd, modifier = Modifier.onGloballyPositioned { onBounds(it.boundsInWindow()) }) {
            IconButton(
                onClick = item.onClick,
                enabled = item.enabled,
                modifier = Modifier
                    .size(ButtonSize)
                    .semantics { contentDescription = item.contentDescription },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = DifftTheme.colors.icon,
                    disabledContentColor = DifftTheme.colors.textDisabled,
                ),
            ) {
                if (item.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = DifftTheme.colors.icon)
                } else {
                    Icon(
                        painter = painterResource(item.icon),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (item.tinted) LocalContentColor.current else Color.Unspecified,
                    )
                }
            }
            if (item.badge > 0) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(18.dp)
                        .background(DifftTheme.colors.error, CircleShape),
                ) {
                    Text(
                        text = if (item.badge > 99) "99+" else item.badge.toString(),
                        style = DifftTheme.typography.p5,
                        color = DifftTheme.colors.textOnPrimary,
                    )
                }
            }
        }
    }
}
