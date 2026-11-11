



package com.difft.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.avatar.AvatarLetterSize
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.common.compose.ContactAvatar


@Composable
internal fun HomeTabContents(
    state: HomeTabCapsuleState,
    vertical: Boolean,
    selectedCopy: Boolean,
    lens: HomeTabLens?,
    onTabClick: (Int) -> Unit,
    onChatsDoubleTap: () -> Unit,
    lastChatsTap: LongArray,
    selectedScale: () -> Float,
) {
    val labels = listOf(
        stringResource(com.difft.android.chat.R.string.chat_chats),
        stringResource(com.difft.android.chat.R.string.chat_contacts),
        stringResource(com.difft.android.chat.R.string.chat_me),
    )
    TabStrip(vertical, Modifier) { slot ->
        repeat(HomeTab.COUNT) { tab ->
            val visual = @Composable {
                TabVisual(tab, homeTabSelectedVisual(selectedCopy, lens != null, state.selected, tab), state, lens, selectedCopy)
            }
            if (selectedCopy) {
                Box(slot.graphicsLayer {
                    scaleX = selectedScale()
                    scaleY = scaleX
                }, contentAlignment = Alignment.Center) { visual() }
            } else {
                TabSlot(tab, slot, state.selected == tab, labels[tab], onClick = {
                    onTabClick(tab)
                    if (tab == HomeTab.CHATS) {
                        val now = System.currentTimeMillis()
                        if (now - lastChatsTap[0] < HomeTab.DOUBLE_TAP_TIMEOUT_MS) {
                            lastChatsTap[0] = 0L
                            onChatsDoubleTap()
                        } else lastChatsTap[0] = now
                    }
                }) { visual() }
            }
        }
    }
}

@Composable
private fun TabVisual(tab: Int, selected: Boolean, state: HomeTabCapsuleState, lens: HomeTabLens?, selectedCopy: Boolean) {
    val colors = DifftTheme.colors
    when (tab) {
        HomeTab.CHATS -> BadgedIcon(homeTabGlyph(tab, selected), colors.icon, state.chatsBadge, semantic = !selectedCopy)
        HomeTab.ME -> {
            val me = state.me?.takeIf { !it.avatar.isNullOrBlank() || !it.name.isNullOrBlank() || !it.remark.isNullOrBlank() }
            if (me == null) {
                Icon(painterResource(homeTabGlyph(tab, selected)), null,
                    Modifier.size(ICON_SIZE).then(if (selectedCopy) Modifier else Modifier.testTag(TAG_ME_ICON)), tint = colors.icon)
            } else if (selectedCopy && lens != null) {
                androidx.compose.foundation.Canvas(Modifier.size(ICON_SIZE)) { lens.drawAvatar(this) }
            } else {
                ContactAvatar(me, size = ICON_SIZE, letterTextSizeDp = AvatarLetterSize.forDiameterDp(ICON_SIZE_DP),
                    modifier = (if (lens == null) Modifier else with(lens) { Modifier.recordAvatar() }).testTag(TAG_ME_AVATAR))
            }
        }
        else -> Icon(painterResource(homeTabGlyph(tab, selected)), null, Modifier.size(ICON_SIZE), tint = colors.icon)
    }
}


internal fun homeTabGlyph(tab: Int, selected: Boolean): Int = when (tab) {
    HomeTab.CHATS -> if (selected) R.drawable.ic_home_tab_chat_filled else R.drawable.ic_index_activity_chat_default
    HomeTab.CONTACTS -> if (selected) R.drawable.ic_home_tab_contact_filled else R.drawable.ic_index_activity_contact_default
    else -> if (selected) R.drawable.ic_home_tab_me_filled else R.drawable.ic_index_activity_me_default
}

internal fun homeTabSelectedVisual(selectedCopy: Boolean, lensEnabled: Boolean, selected: Int, tab: Int): Boolean =
    selectedCopy || (!lensEnabled && selected == tab)


@Composable
private fun TabStrip(vertical: Boolean, modifier: Modifier, content: @Composable (slot: Modifier) -> Unit) {
    if (vertical) {
        Column(modifier = modifier) { content(Modifier.size(RAIL_SLOT_WIDTH, RAIL_SLOT_HEIGHT)) }
    } else {
        Row(modifier = modifier) { content(Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun TabSlot(
    tab: Int,
    modifier: Modifier,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Tab, onClick = onClick)
            
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .testTag(slotTag(tab)),
    ) {
        
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .indication(interactionSource, ripple())
        )
        content()
    }
}

@Composable
private fun BadgedIcon(painterRes: Int, tint: Color, badge: ChatsBadge, semantic: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(painterRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(ICON_SIZE),
        )
        when (badge) {
            is ChatsBadge.Count -> Box(
                
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = BADGE_OFFSET_X, y = BADGE_OFFSET_Y)
                    .height(BADGE_HEIGHT)
                    .widthIn(min = BADGE_HEIGHT)
                    .background(badgeColor(badge.muted), CircleShape)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = if (badge.count > 99) "99+" else badge.count.toString(),
                    color = DifftTheme.colors.textOnPrimary,
                    style = DifftTheme.typography.p5,
                    maxLines = 1,
                    modifier = if (semantic) Modifier.testTag(TAG_BADGE) else Modifier,
                )
            }
            is ChatsBadge.Dot -> Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = BADGE_OFFSET_X, y = BADGE_OFFSET_Y)
                    .size(DOT_SIZE)
                    .background(badgeColor(badge.muted), CircleShape)
                    .then(if (semantic) Modifier.testTag(TAG_BADGE_DOT) else Modifier),
            )
            ChatsBadge.None -> Unit
        }
    }
}


@Composable
private fun badgeColor(muted: Boolean): Color =
    if (muted) {
        if (DifftTheme.colors.isDark) DifftTheme.colors.backgroundQuaternary else DifftTheme.colors.textDisabled
    } else {
        DifftTheme.colors.error
    }

private const val ICON_SIZE_DP = 24
private val ICON_SIZE = ICON_SIZE_DP.dp
private val RAIL_SLOT_WIDTH = 40.dp
private val RAIL_SLOT_HEIGHT = 48.dp
private val BADGE_HEIGHT = 16.dp
private val DOT_SIZE = 10.dp
private val BADGE_OFFSET_X = ICON_SIZE - 8.dp
private val BADGE_OFFSET_Y = (-2).dp
