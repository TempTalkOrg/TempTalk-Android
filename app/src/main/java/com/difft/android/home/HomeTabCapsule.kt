



package com.difft.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.compose.floatingPill
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.theme.DifftTheme
import org.difft.app.database.models.ContactorModel


sealed interface ChatsBadge {
    data object None : ChatsBadge
    data class Count(val count: Int, val muted: Boolean) : ChatsBadge
    data class Dot(val muted: Boolean) : ChatsBadge
}





@Immutable
data class HomeTabCapsuleState(
    val selected: Int = HomeTab.CHATS,
    val chatsBadge: ChatsBadge = ChatsBadge.None,
    val me: ContactorModel? = null,
)

object HomeTab {
    const val CHATS = 0
    const val CONTACTS = 1
    const val ME = 2
    const val COUNT = 3

    
    const val DOUBLE_TAP_TIMEOUT_MS = 300L
}











@Composable
fun HomeTabCapsule(
    state: HomeTabCapsuleState,
    onTabClick: (Int) -> Unit,
    onChatsDoubleTap: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchFrom: (android.graphics.Rect) -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier.padding(start = OUTER_PADDING, end = OUTER_PADDING, top = OUTER_PADDING)) {
        val groupWidth = minOf(GROUP_MAX_WIDTH, maxWidth)
        val capsuleWidth = (groupWidth - SEARCH_SIZE - GROUP_GAP).coerceAtLeast(CAPSULE_MIN_WIDTH)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Capsule(state, onTabClick, onChatsDoubleTap, Modifier.width(capsuleWidth))
            Spacer(Modifier.width(GROUP_GAP))
            SearchCircle(onSearch, onSearchFrom)
        }
    }
}





@Composable
fun HomeTabRail(
    state: HomeTabCapsuleState,
    onTabClick: (Int) -> Unit,
    onChatsDoubleTap: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchFrom: (android.graphics.Rect) -> Unit = {},
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Capsule(state, onTabClick, onChatsDoubleTap, Modifier.width(RAIL_ITEM_WIDTH), vertical = true)
        Spacer(Modifier.height(GROUP_GAP))
        SearchCircle(onSearch, onSearchFrom, size = RAIL_ITEM_WIDTH)
    }
}

@Composable
private fun Capsule(
    state: HomeTabCapsuleState,
    onTabClick: (Int) -> Unit,
    onChatsDoubleTap: () -> Unit,
    modifier: Modifier,
    vertical: Boolean = false,
) {
    val lastChatsTap = remember { LongArray(1) }
    val surface = modifier.then(if (vertical) Modifier else Modifier.height(CAPSULE_HEIGHT))
    val motion = rememberPillMotion(state.selected) {
        lastChatsTap[0] = 0L
        onTabClick(it)
    }
    val lensEnabled = GlassMode.isLiquid(LocalContext.current)
    val press by motion.pressState
    Box(surface.graphicsLayer {
        if (lensEnabled) {
            val shift = HomeTabPanelMath.offset(motion.panelDisplacement, maxOf(size.width, size.height), 4.dp.toPx())
            translationX = HomeTabLensMath.physicalX(shift, vertical)
            translationY = HomeTabLensMath.physicalY(shift, vertical)
        }
    }.testTag(TAG_CAPSULE)) {
        
        Box(Modifier.matchParentSize().graphicsLayer {
            if (lensEnabled) {
                val scale = HomeTabPanelMath.scale(press, maxOf(size.width, size.height), 16.dp.toPx())
                scaleX = scale
                scaleY = scale
            }
        }.testTag(TAG_CAPSULE_PANEL).then(if (lensEnabled) Modifier.homeTabPanel() else Modifier.floatingPill(glass = true)))
        HomeTabSelectionPill(
            motion = motion,
            vertical = vertical,
            pressProgress = { press },
            modifier = Modifier.padding(SLOT_PADDING),
        ) { selectedCopy, lens ->
            HomeTabContents(state, vertical, selectedCopy, lens, onTabClick, onChatsDoubleTap, lastChatsTap,
                selectedScale = { HomeTabPanelMath.selectedContentScale(press) })
        }
    }
}






@Composable
private fun SearchCircle(
    onSearch: () -> Unit,
    onSearchFrom: (android.graphics.Rect) -> Unit,
    size: Dp = SEARCH_SIZE,
) {
    val label = stringResource(com.difft.android.base.R.string.search_default_hint)
    val bounds = remember { arrayOfNulls<android.graphics.Rect>(1) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .onGloballyPositioned { bounds[0] = it.boundsInWindow().toAndroidRect() }
            .floatingPill(glass = true)
            .clickable(role = Role.Button) {
                bounds[0]?.let(onSearchFrom)
                onSearch()
            }
            .semantics { contentDescription = label }
            .testTag(TAG_SEARCH),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_home_tab_search),
            contentDescription = null,
            tint = DifftTheme.colors.icon,
            modifier = Modifier.size(24.dp),
        )
    }
}

internal fun slotTag(tab: Int): String = "home_tab_slot_$tab"

internal const val TAG_CAPSULE = "home_tab_capsule"
internal const val TAG_CAPSULE_PANEL = "home_tab_capsule_panel"
internal const val TAG_SEARCH = "home_tab_search"
internal const val TAG_BADGE = "home_tab_badge"
internal const val TAG_BADGE_DOT = "home_tab_badge_dot"
internal const val TAG_ME_AVATAR = "home_tab_me_avatar"
internal const val TAG_ME_ICON = "home_tab_me_icon"

private const val SLOT_MIN_WIDTH_DP = 48
private val OUTER_PADDING: Dp = 16.dp

private val GROUP_MAX_WIDTH: Dp = 348.dp
private val GROUP_GAP: Dp = 12.dp
private val SEARCH_SIZE: Dp = 62.dp
private val CAPSULE_HEIGHT: Dp = 62.dp
private val SLOT_PADDING: Dp = 4.dp

private val RAIL_ITEM_WIDTH: Dp = 48.dp

private val CAPSULE_MIN_WIDTH: Dp = (HomeTab.COUNT * SLOT_MIN_WIDTH_DP).dp + SLOT_PADDING * 2
