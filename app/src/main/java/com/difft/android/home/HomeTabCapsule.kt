package com.difft.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.avatar.AvatarLetterSize
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.common.compose.ContactAvatar
import org.difft.app.database.models.ContactorModel

/** Chats tab badge: a real unread count, or a dot for a manual "mark as unread" (#1332). */
sealed interface ChatsBadge {
    data object None : ChatsBadge
    data class Count(val count: Int, val muted: Boolean) : ChatsBadge
    data class Dot(val muted: Boolean) : ChatsBadge
}

/**
 * State of the floating home tab capsule. [me] is the user's own contact for the Me tab's
 * avatar; null (not loaded yet, e.g. before the first contact sync) falls back to the Me icon.
 */
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

    /** Two Chats taps closer than this count as the chat-list "jump to next unread" gesture. */
    const val DOUBLE_TAP_TIMEOUT_MS = 300L
}

/**
 * Floating icon-only home tab capsule plus the universal-search circle, the same chrome iOS
 * draws (`DTQuicallTabChrome`): a 62dp pill with three equal pill slots, a 62dp search circle
 * 12dp to its right, the pair centred and at most 348dp wide; narrower hosts shrink the slots
 * (never below 48dp) while the circle keeps its size. The selected slot gets a translucent
 * bg4 pill and the filled glyph, the Me slot shows the user's avatar, only Chats carries a
 * badge. Solid, slightly translucent bg1 with a hairline and a soft shadow (no glass on
 * Android). The 16dp outer padding keeps the shadow inside this composable's own bounds; the
 * host provides the bottom gap.
 */
@Composable
fun HomeTabCapsule(
    state: HomeTabCapsuleState,
    onTabClick: (Int) -> Unit,
    onChatsDoubleTap: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.padding(start = OUTER_PADDING, end = OUTER_PADDING, top = OUTER_PADDING)) {
        val groupWidth = minOf(GROUP_MAX_WIDTH, maxWidth)
        val capsuleWidth = (groupWidth - SEARCH_SIZE - GROUP_GAP).coerceAtLeast(CAPSULE_MIN_WIDTH)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Capsule(state, onTabClick, onChatsDoubleTap, Modifier.width(capsuleWidth))
            Spacer(Modifier.width(GROUP_GAP))
            SearchCircle(onSearch)
        }
    }
}

@Composable
private fun Capsule(
    state: HomeTabCapsuleState,
    onTabClick: (Int) -> Unit,
    onChatsDoubleTap: () -> Unit,
    modifier: Modifier,
) {
    val colors = DifftTheme.colors
    val lastChatsTap = remember { LongArray(1) }
    Row(
        modifier = modifier
            .testTag(TAG_CAPSULE)
            .height(CAPSULE_HEIGHT)
            .floatingSurface()
            .padding(SLOT_PADDING),
    ) {
        TabSlot(
            tab = HomeTab.CHATS,
            selected = state.selected == HomeTab.CHATS,
            label = stringResource(com.difft.android.chat.R.string.chat_chats),
            onClick = {
                val now = System.currentTimeMillis()
                onTabClick(HomeTab.CHATS)
                if (now - lastChatsTap[0] < HomeTab.DOUBLE_TAP_TIMEOUT_MS) {
                    lastChatsTap[0] = 0L
                    onChatsDoubleTap()
                } else {
                    lastChatsTap[0] = now
                }
            },
        ) { selected ->
            BadgedIcon(
                painterRes = if (selected) R.drawable.ic_home_tab_chat_filled else R.drawable.ic_index_activity_chat_default,
                tint = if (selected) colors.textPrimary else colors.icon,
                badge = state.chatsBadge,
            )
        }
        TabSlot(
            tab = HomeTab.CONTACTS,
            selected = state.selected == HomeTab.CONTACTS,
            label = stringResource(com.difft.android.chat.R.string.chat_contacts),
            onClick = { onTabClick(HomeTab.CONTACTS) },
        ) { selected ->
            Icon(
                painter = painterResource(
                    if (selected) R.drawable.ic_home_tab_contact_filled else R.drawable.ic_index_activity_contact_default
                ),
                contentDescription = null,
                tint = if (selected) colors.textPrimary else colors.icon,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        TabSlot(
            tab = HomeTab.ME,
            selected = state.selected == HomeTab.ME,
            label = stringResource(com.difft.android.chat.R.string.chat_me),
            onClick = { onTabClick(HomeTab.ME) },
        ) { selected ->
            // A contact with neither picture nor name would render as a coloured ID letter;
            // the neutral Me icon is the fallback for that state too.
            val me = state.me?.takeIf {
                !it.avatar.isNullOrBlank() || !it.name.isNullOrBlank() || !it.remark.isNullOrBlank()
            }
            if (me != null) {
                ContactAvatar(
                    contactor = me,
                    size = ICON_SIZE,
                    letterTextSizeDp = AvatarLetterSize.forDiameterDp(ICON_SIZE_DP),
                    modifier = Modifier.testTag(TAG_ME_AVATAR),
                )
            } else {
                Icon(
                    painter = painterResource(
                        if (selected) R.drawable.ic_home_tab_me_filled else R.drawable.ic_index_activity_me_default
                    ),
                    contentDescription = null,
                    tint = if (selected) colors.textPrimary else colors.icon,
                    modifier = Modifier
                        .size(ICON_SIZE)
                        .testTag(TAG_ME_ICON),
                )
            }
        }
    }
}

/** The universal-search entry: a circle the size of the capsule, outside it (iOS, prototype s12). */
@Composable
private fun SearchCircle(onSearch: () -> Unit) {
    val label = stringResource(com.difft.android.base.R.string.search_default_hint)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(SEARCH_SIZE)
            .floatingSurface()
            .clickable(role = Role.Button, onClick = onSearch)
            .semantics { contentDescription = label }
            .testTag(TAG_SEARCH),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_home_tab_search),
            contentDescription = null,
            tint = DifftTheme.colors.icon,
            modifier = Modifier.size(ICON_SIZE),
        )
    }
}

/** Shared skin of the capsule and the search circle: outer shadow, translucent bg1, hairline. */
@Composable
private fun Modifier.floatingSurface(): Modifier {
    val colors = DifftTheme.colors
    val dark = colors.background.luminance() < DARK_LUMINANCE
    return this
        .outerShadow(alpha = if (dark) SHADOW_ALPHA_DARK else SHADOW_ALPHA_LIGHT)
        // Translucent surface (design-system glass-bg, 84%): the list shows through faintly;
        // a real backdrop blur needs the list re-rendered under the chrome and is a separate
        // investigation.
        .background(colors.background.copy(alpha = CAPSULE_SURFACE_ALPHA), CircleShape)
        .border(0.5.dp, colors.line, CircleShape)
        .clip(CircleShape)
}

/**
 * A soft pill shadow drawn only outside the pill. An elevation shadow (`Modifier.shadow`) is
 * rendered underneath the caster, so a translucent surface shows its tessellated polygon
 * through (an octagon on a light list). Clipping the pill out keeps the inside clean. Hardware
 * canvases draw `setShadowLayer` for shapes from API 28; older devices simply get no shadow.
 */
private fun Modifier.outerShadow(alpha: Float): Modifier = drawBehind {
    val radius = size.height / 2
    val pill = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
    val shadowColor = Color.Black.copy(alpha = alpha).toArgb()
    val blur = SHADOW_BLUR.toPx()
    val dy = SHADOW_OFFSET_Y.toPx()
    clipPath(pill, clipOp = ClipOp.Difference) {
        drawIntoCanvas { canvas ->
            val paint = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                color = shadowColor
                setShadowLayer(blur, 0f, dy, shadowColor)
            }
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
        }
    }
}

@Composable
private fun RowScope.TabSlot(
    tab: Int,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    content: @Composable (selected: Boolean) -> Unit,
) {
    // Translucent like the capsule itself, so the list still shows through the selected slot.
    val pill = if (selected) DifftTheme.colors.backgroundQuaternary.copy(alpha = PILL_ALPHA) else Color.Transparent
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Tab, onClick = onClick)
            // Icon-only capsule: the tab name lives in the accessibility label only.
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .testTag(slotTag(tab)),
    ) {
        // Only the pill and its ripple are clipped to the slot shape: the Chats badge grows to the
        // right of the glyph and must not be cut where a narrow list pane shrinks the slot.
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(pill)
                .indication(interactionSource, ripple())
        )
        content(selected)
    }
}

@Composable
private fun BadgedIcon(painterRes: Int, tint: Color, badge: ChatsBadge) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(painterRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(ICON_SIZE),
        )
        when (badge) {
            is ChatsBadge.Count -> Box(
                // The pill owns the size so the count sits centred inside it.
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
                    color = DifftTheme.colors.bgWhite,
                    style = DifftTheme.typography.p5,
                    maxLines = 1,
                    modifier = Modifier.testTag(TAG_BADGE),
                )
            }
            is ChatsBadge.Dot -> Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = BADGE_OFFSET_X, y = BADGE_OFFSET_Y)
                    .size(DOT_SIZE)
                    .background(badgeColor(badge.muted), CircleShape)
                    .testTag(TAG_BADGE_DOT),
            )
            ChatsBadge.None -> Unit
        }
    }
}

// Muted unread uses the disabled-text grey (the same #B7BDC6 as the chat-list row's muted badge).
@Composable
private fun badgeColor(muted: Boolean): Color =
    if (muted) DifftTheme.colors.textDisabled else DifftTheme.colors.error

internal fun slotTag(tab: Int): String = "home_tab_slot_$tab"

internal const val TAG_CAPSULE = "home_tab_capsule"
internal const val TAG_SEARCH = "home_tab_search"
internal const val TAG_BADGE = "home_tab_badge"
internal const val TAG_BADGE_DOT = "home_tab_badge_dot"
internal const val TAG_ME_AVATAR = "home_tab_me_avatar"
internal const val TAG_ME_ICON = "home_tab_me_icon"

private const val CAPSULE_SURFACE_ALPHA = 0.84f
private const val PILL_ALPHA = 0.55f
private const val ICON_SIZE_DP = 24
private const val SLOT_MIN_WIDTH_DP = 48
private val OUTER_PADDING: Dp = 16.dp
// Design-system shadow-bar: a soft 12/32 drop at 8% (light) / 60% (dark) black.
private val SHADOW_BLUR: Dp = 12.dp
private val SHADOW_OFFSET_Y: Dp = 4.dp
private const val SHADOW_ALPHA_LIGHT = 0.10f
private const val SHADOW_ALPHA_DARK = 0.5f
private const val DARK_LUMINANCE = 0.5f
/** Capsule 274 + gap 12 + search 62: the iOS/prototype group width on a phone. */
private val GROUP_MAX_WIDTH: Dp = 348.dp
private val GROUP_GAP: Dp = 12.dp
private val SEARCH_SIZE: Dp = 62.dp
private val CAPSULE_HEIGHT: Dp = 62.dp
private val SLOT_PADDING: Dp = 4.dp
/** Three 48dp slots inside the 4dp padding: the narrowest the capsule may shrink to. */
private val CAPSULE_MIN_WIDTH: Dp = (HomeTab.COUNT * SLOT_MIN_WIDTH_DP).dp + SLOT_PADDING * 2
private val ICON_SIZE: Dp = ICON_SIZE_DP.dp
private val BADGE_HEIGHT: Dp = 16.dp
private val DOT_SIZE: Dp = 10.dp
// Badge anchored at the icon's top-right corner and growing to the right, so "99+" never covers the glyph.
private val BADGE_OFFSET_X: Dp = ICON_SIZE - 8.dp
private val BADGE_OFFSET_Y: Dp = (-2).dp
