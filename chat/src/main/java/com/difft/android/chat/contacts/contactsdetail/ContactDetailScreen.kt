package com.difft.android.chat.contacts.contactsdetail

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import org.difft.app.database.models.ContactorModel

/**
 * Contact detail UI state
 */
data class ContactDetailUiState(
    val contactor: ContactorModel? = null,
    val isFriend: Boolean = true,
    /** Weak-pending (delayed-removal) contact: isFriend=false + this true → show "Remove Now". */
    val isWeakPending: Boolean = false,
    val isSelf: Boolean = false,
    val isOfficialAccount: Boolean = false,
    val displayName: String = "",
    val originalName: String? = null,
    val hasRemark: Boolean = false,
    val hasRemarkAvatar: Boolean = false,
    /** Public (non-remark) avatar JSON, rendered as the inline 20dp original avatar in the subtitle. */
    val originalAvatarJson: String? = null,
    val userId: String = "",
    val joinedAt: String? = null,
    val sourceDescribe: String? = null,
    val commonGroupsCount: Int = 0,
    val website: String? = null
)

/**
 * Contact detail screen composable
 * @param uiState UI state containing contact info
 * @param isPopupMode Whether displayed in popup (BottomSheet) mode
 * @param onCloseClick Close button click callback
 * @param onMoreClick More button click callback
 * @param onAvatarClick Avatar click callback for preview
 * @param onEditClick Edit name click callback
 * @param onMessageClick Message button click callback
 * @param onCallClick Call button click callback
 * @param onShareClick Share button click callback
 * @param onAddFriendClick Add friend button click callback
 * @param onCommonGroupsClick Common groups click callback
 * @param onCopyUserId Copy user ID click callback
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun ContactDetailScreen(
    uiState: ContactDetailUiState,
    isPopupMode: Boolean,
    onCloseClick: () -> Unit,
    onMoreClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onOriginalAvatarClick: () -> Unit,
    onEditClick: () -> Unit,
    onMessageClick: () -> Unit,
    onCallClick: () -> Unit,
    onShareClick: () -> Unit,
    onAddFriendClick: () -> Unit,
    onCommonGroupsClick: () -> Unit,
    onCopyUserId: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    onWebsiteClick: () -> Unit = {},
    onRemoveNowClick: () -> Unit = {}
) {
    // Popup mode: calculate minimum height (40% of screen height) to avoid looking too short.
    // Fall back to Configuration on the first composition (before the first layout pass), otherwise
    // containerSize.height is 0 and the popup would render with 0 min height for the first frame.
    val minHeight = if (isPopupMode) {
        val containerHeight = LocalWindowInfo.current.containerSize.height
        val heightDp = if (containerHeight > 0) {
            with(LocalDensity.current) { containerHeight.toDp() }
        } else {
            LocalConfiguration.current.screenHeightDp.dp
        }
        heightDp * 0.4f
    } else {
        0.dp
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isPopupMode) {
                    // Popup mode: wrap content height with minimum height
                    Modifier
                        .wrapContentHeight()
                        .heightIn(min = minHeight)
                } else {
                    // Full screen mode: fill entire screen
                    Modifier.fillMaxSize()
                }
            )
            .background(DifftTheme.colors.bg)
    ) {
        // Top bar (fixed)
        TopBar(
            isPopupMode = isPopupMode,
            showBackButton = showBackButton,
            showMoreButton = uiState.isFriend && !uiState.isSelf,
            showEditButton = !uiState.isSelf,
            onCloseClick = onCloseClick,
            onMoreClick = onMoreClick,
            onEditClick = onEditClick
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackMedium))

        // Avatar and name section (with horizontal padding)
        AvatarNameSection(
            contactor = uiState.contactor,
            displayName = uiState.displayName,
            originalName = uiState.originalName,
            hasRemark = uiState.hasRemark,
            hasRemarkAvatar = uiState.hasRemarkAvatar,
            originalAvatarJson = uiState.originalAvatarJson,
            isOfficialAccount = uiState.isOfficialAccount,
            onAvatarClick = onAvatarClick,
            onOriginalAvatarClick = onOriginalAvatarClick,
            modifier = Modifier.padding(horizontal = DifftTheme.spacing.insetLarge)
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackMedium))

        // Action buttons (with horizontal padding)
        ActionButtonsSection(
            isSelf = uiState.isSelf,
            isFriend = uiState.isFriend,
            isWeakPending = uiState.isWeakPending,
            isOfficialAccount = uiState.isOfficialAccount,
            onMessageClick = onMessageClick,
            onCallClick = onCallClick,
            onShareClick = onShareClick,
            onAddFriendClick = onAddFriendClick,
            onRemoveNowClick = onRemoveNowClick,
            modifier = Modifier.padding(horizontal = DifftTheme.spacing.insetLarge)
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackMedium))

        // Contact info section - dynamic layout based on mode
        ContactInfoSection(
            userId = uiState.userId,
            joinedAt = uiState.joinedAt,
            sourceDescribe = uiState.sourceDescribe,
            commonGroupsCount = uiState.commonGroupsCount,
            isSelf = uiState.isSelf,
            isOfficialAccount = uiState.isOfficialAccount,
            website = uiState.website,
            onCommonGroupsClick = onCommonGroupsClick,
            onCopyUserId = onCopyUserId,
            onWebsiteClick = onWebsiteClick,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isPopupMode) {
                        // Popup mode: wrap content height
                        Modifier.wrapContentHeight()
                    } else {
                        // Full screen mode: fill remaining space, gray background extends to bottom
                        Modifier.weight(1f)
                    }
                )
        )
    }
}

@Composable
private fun TopBar(
    isPopupMode: Boolean,
    showBackButton: Boolean,
    showMoreButton: Boolean,
    showEditButton: Boolean,
    onCloseClick: () -> Unit,
    onMoreClick: () -> Unit,
    onEditClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(DifftTheme.spacing.insetLarge),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Close/Back button - show in popup mode or when showBackButton is true
        if (isPopupMode || showBackButton) {
            Icon(
                painter = painterResource(
                    id = if (isPopupMode) R.drawable.ic_close else R.drawable.chat_contact_detail_ic_back
                ),
                contentDescription = null,
                modifier = Modifier
                    .clickable { onCloseClick() }
                    .padding(10.dp)
                    .size(DifftTheme.spacing.iconMedium),
                tint = DifftTheme.colors.textPrimary
            )
        } else {
            // Placeholder when back button is hidden
            Spacer(modifier = Modifier.size(DifftTheme.spacing.iconMedium + 20.dp))
        }

        // Right side: edit + more buttons
        // Each icon uses padding(10.dp) to form a 44dp square touch target;
        // adjacent icons naturally share their padding gaps without extra spacedBy.
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showEditButton) {
                Icon(
                    painter = painterResource(id = R.drawable.chat_contact_detail_ic_edit),
                    contentDescription = null,
                    modifier = Modifier
                        .clickable { onEditClick() }
                        .padding(10.dp)
                        .size(DifftTheme.spacing.iconMedium),
                    tint = DifftTheme.colors.textPrimary
                )
            }
            if (showMoreButton) {
                Icon(
                    painter = painterResource(id = R.drawable.chat_message_action_more),
                    contentDescription = null,
                    modifier = Modifier
                        .clickable { onMoreClick() }
                        .padding(10.dp)
                        .size(DifftTheme.spacing.iconMedium),
                    tint = DifftTheme.colors.textPrimary
                )
            } else if (!showEditButton) {
                Spacer(modifier = Modifier.size(DifftTheme.spacing.iconMedium + 20.dp))
            }
        }
    }
}

@Composable
private fun AvatarNameSection(
    contactor: ContactorModel?,
    displayName: String,
    originalName: String?,
    hasRemark: Boolean,
    hasRemarkAvatar: Boolean,
    originalAvatarJson: String?,
    isOfficialAccount: Boolean,
    onAvatarClick: () -> Unit,
    onOriginalAvatarClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar using AndroidView to embed AvatarView
        Box(
            modifier = Modifier
                .size(DifftTheme.spacing.avatarLarge)
                .clip(CircleShape)
                .clickable { onAvatarClick() }
        ) {
            contactor?.let { contact ->
                val avatarSizePx = with(LocalDensity.current) {
                    DifftTheme.spacing.avatarLarge.roundToPx()
                }
                AndroidView(
                    factory = { ctx -> AvatarView(ctx) },
                    update = { avatarView ->
                        // Pass explicit size to avoid layout timing issues in Compose AndroidView
                        avatarView.setAvatar(contact, 22, avatarSizePx)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.width(DifftTheme.spacing.inlineMedium))

        // Names column - takes remaining width after avatar
        Column(modifier = Modifier.weight(1f)) {
            // Display name and edit button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = TextStyle(
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp
                    ),
                    color = DifftTheme.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (isOfficialAccount) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.chat_ic_official_bot_badge),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.Unspecified
                    )
                }
            }

            val showSubtitle = isOfficialAccount ||
                (hasRemark && !originalName.isNullOrEmpty()) ||
                hasRemarkAvatar
            if (showSubtitle) {
                Spacer(modifier = Modifier.height(4.dp))
                SubtitleRow(
                    isOfficialAccount = isOfficialAccount,
                    hasRemark = hasRemark,
                    hasRemarkAvatar = hasRemarkAvatar,
                    originalName = originalName,
                    originalAvatarJson = originalAvatarJson,
                    contactor = contactor,
                    onOriginalAvatarClick = onOriginalAvatarClick,
                )
            }
        }
    }
}

private val subtitleStyle = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp
)

@Composable
private fun SubtitleRow(
    isOfficialAccount: Boolean,
    hasRemark: Boolean,
    hasRemarkAvatar: Boolean,
    originalName: String?,
    originalAvatarJson: String?,
    contactor: ContactorModel?,
    onOriginalAvatarClick: () -> Unit,
) {
    val tertiary = DifftTheme.colors.textTertiary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isOfficialAccount) {
            Text(
                text = stringResource(R.string.contact_official_account_label),
                style = subtitleStyle,
                color = tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if ((hasRemark && !originalName.isNullOrEmpty()) || hasRemarkAvatar) {
                Text(text = "・", style = subtitleStyle, color = tertiary)
            }
        }
        when {
            // Both remarked: inline "[smallAvatar] originalName" without key labels.
            hasRemark && hasRemarkAvatar && !originalName.isNullOrEmpty() -> {
                SmallOriginalAvatar(originalAvatarJson, contactor, onOriginalAvatarClick)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = originalName,
                    style = subtitleStyle,
                    color = tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Only name remarked: "Name: originalName"
            hasRemark && !originalName.isNullOrEmpty() -> {
                Text(
                    text = stringResource(R.string.contact_name_label, originalName),
                    style = subtitleStyle,
                    color = tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Only avatar remarked: "Original: [smallAvatar]"
            hasRemarkAvatar -> {
                Text(
                    text = stringResource(R.string.contact_avatar_label),
                    style = subtitleStyle,
                    color = tertiary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                SmallOriginalAvatar(originalAvatarJson, contactor, onOriginalAvatarClick)
            }
        }
    }
}

@Composable
private fun SmallOriginalAvatar(
    originalAvatarJson: String?,
    contactor: ContactorModel?,
    onClick: () -> Unit,
) {
    val avatarSizePx = with(LocalDensity.current) { 20.dp.roundToPx() }
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        AndroidView(
            factory = { ctx -> AvatarView(ctx) },
            update = { avatarView ->
                val avatarData = originalAvatarJson?.getContactAvatarData()
                avatarView.setAvatar(
                    url = avatarData?.getContactAvatarUrl(),
                    key = avatarData?.encKey,
                    firstLetter = ContactorUtil.getFirstLetter(
                        contactor?.getDisplayNameWithoutRemarkForUI().orEmpty()
                    ),
                    id = contactor?.id.orEmpty(),
                    letterTextSizeDp = 11,
                    targetSizePx = avatarSizePx,
                )
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ActionButtonsSection(
    isSelf: Boolean,
    isFriend: Boolean,
    isWeakPending: Boolean,
    isOfficialAccount: Boolean,
    onMessageClick: () -> Unit,
    onCallClick: () -> Unit,
    onShareClick: () -> Unit,
    onAddFriendClick: () -> Unit,
    onRemoveNowClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DifftTheme.spacing.inlineSmall)
    ) {
        when {
            isSelf -> {
                // Self: show message and share buttons (each takes 50%)
                ActionButton(
                    iconRes = R.drawable.chat_contact_detail_ic_message,
                    label = stringResource(R.string.contact_action_message),
                    onClick = onMessageClick,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    iconRes = R.drawable.chat_contact_detail_ic_share,
                    label = stringResource(R.string.contact_action_share),
                    onClick = onShareClick,
                    modifier = Modifier.weight(1f)
                )
            }

            isFriend -> {
                // Friend: show message, call (if not bot), share
                ActionButton(
                    iconRes = R.drawable.chat_contact_detail_ic_message,
                    label = stringResource(R.string.contact_action_message),
                    onClick = onMessageClick,
                    modifier = Modifier.weight(1f)
                )
                if (!isOfficialAccount) {
                    ActionButton(
                        iconRes = R.drawable.chat_contact_detail_ic_call,
                        label = stringResource(R.string.contact_action_call),
                        onClick = onCallClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                ActionButton(
                    iconRes = R.drawable.chat_contact_detail_ic_share,
                    label = stringResource(R.string.contact_action_share),
                    onClick = onShareClick,
                    modifier = Modifier.weight(1f)
                )
            }

            else -> {
                // Non-friend: show "Add Contact" button, which adds friend then navigates to chat
                ActionButton(
                    iconRes = R.drawable.chat_icon_add_contact,
                    label = stringResource(R.string.contact_add_contacts),
                    onClick = onAddFriendClick,
                    modifier = Modifier.weight(1f)
                )
                // Weak-pending (delayed-removal) contact: also offer "Remove Now".
                if (isWeakPending) {
                    ActionButton(
                        iconRes = R.drawable.chat_message_action_delete,
                        label = stringResource(R.string.weak_contact_remove_now),
                        onClick = onRemoveNowClick,
                        tint = DifftTheme.colors.error,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    val iconTint = tint ?: DifftTheme.colors.icon
    val labelColor = tint ?: DifftTheme.colors.textPrimary
    Column(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(DifftTheme.spacing.inlineSmall))
            .background(DifftTheme.colors.bgElevated)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            modifier = Modifier.size(DifftTheme.spacing.iconMedium),
            tint = iconTint
        )
        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackSmall))
        Text(
            text = label,
            style = DifftTheme.typography.labelMedium,
            color = labelColor
        )
    }
}

@Composable
private fun ContactInfoSection(
    userId: String,
    joinedAt: String?,
    sourceDescribe: String?,
    commonGroupsCount: Int,
    isSelf: Boolean,
    isOfficialAccount: Boolean,
    website: String?,
    onCommonGroupsClick: () -> Unit,
    onCopyUserId: () -> Unit,
    onWebsiteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(DifftTheme.colors.bgElevated)
            .padding(DifftTheme.spacing.insetLarge)
    ) {
        Text(
            text = stringResource(R.string.contact_info_title),
            style = DifftTheme.typography.titleMedium,
            color = DifftTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackMedium))

        // ID with long press copy functionality
        if (userId.isNotEmpty()) {
            ContactInfoRow(
                label = stringResource(R.string.contact_name_profile),
                value = userId,
                onLongClick = onCopyUserId
            )
        }

        // Joined at
        if (!joinedAt.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            ContactInfoRow(
                label = stringResource(R.string.contact_join_at),
                value = joinedAt
            )
        }

        // How you met
        if (!isSelf && !sourceDescribe.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            ContactInfoRow(
                label = stringResource(R.string.contact_how_you_met),
                value = sourceDescribe
            )
        }

        // Common groups (hidden for bots)
        if (!isSelf && !isOfficialAccount) {
            Spacer(modifier = Modifier.height(16.dp))
            ContactInfoRow(
                label = stringResource(R.string.chat_group_in_common),
                value = commonGroupsCount.toString(),
                showArrow = commonGroupsCount > 0,
                onClick = if (commonGroupsCount > 0) onCommonGroupsClick else null
            )
        }

        // Website (only for official account)
        if (isOfficialAccount && !website.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            ContactInfoRow(
                label = stringResource(R.string.contact_info_website),
                value = website,
                isLink = true,
                onClick = onWebsiteClick
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactInfoRow(
    label: String,
    value: String,
    showArrow: Boolean = false,
    isLink: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = DifftTheme.typography.infoLabel,
            color = DifftTheme.colors.textPrimary,
            modifier = Modifier.width(112.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = value,
            style = DifftTheme.typography.infoLabel,
            color = if (isLink) DifftTheme.colors.primary else DifftTheme.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = { onClick?.invoke() },
                            onLongClick = onLongClick
                        )
                    } else {
                        Modifier
                    }
                )
        )

        if (showArrow) {
            Spacer(modifier = Modifier.width(DifftTheme.spacing.inlineXSmall))
            Icon(
                painter = painterResource(id = R.drawable.chat_ic_arrow_right),
                contentDescription = null,
                modifier = Modifier.size(DifftTheme.spacing.iconXSmall),
                tint = DifftTheme.colors.textSecondary
            )
        }
    }
}

@Preview(showBackground = true, name = "Friend Contact")
@Composable
private fun ContactDetailScreenPreview() {
    DifftTheme {
        ContactDetailScreen(
            uiState = ContactDetailUiState(
                displayName = "John Doe",
                originalName = "john_doe",
                hasRemark = true,
                userId = "john_doe",
                joinedAt = "2024-01-15",
                sourceDescribe = "Search",
                commonGroupsCount = 3,
                isFriend = true,
                isSelf = false,
                isOfficialAccount = false
            ),
            isPopupMode = false,
            onCloseClick = {},
            onMoreClick = {},
            onAvatarClick = {},
            onOriginalAvatarClick = {},
            onEditClick = {},
            onMessageClick = {},
            onCallClick = {},
            onShareClick = {},
            onAddFriendClick = {},
            onCommonGroupsClick = {},
            onCopyUserId = {}
        )
    }
}

@Preview(showBackground = true, name = "Self Contact")
@Composable
private fun ContactDetailScreenSelfPreview() {
    DifftTheme {
        ContactDetailScreen(
            uiState = ContactDetailUiState(
                displayName = "Me",
                userId = "my_id",
                joinedAt = "2023-06-01",
                isFriend = true,
                isSelf = true,
                isOfficialAccount = false
            ),
            isPopupMode = false,
            onCloseClick = {},
            onMoreClick = {},
            onAvatarClick = {},
            onOriginalAvatarClick = {},
            onEditClick = {},
            onMessageClick = {},
            onCallClick = {},
            onShareClick = {},
            onAddFriendClick = {},
            onCommonGroupsClick = {},
            onCopyUserId = {}
        )
    }
}

@Preview(showBackground = true, name = "Non-Friend Contact")
@Composable
private fun ContactDetailScreenNonFriendPreview() {
    DifftTheme {
        ContactDetailScreen(
            uiState = ContactDetailUiState(
                displayName = "Stranger",
                userId = "stranger_id",
                joinedAt = "2024-03-20",
                commonGroupsCount = 1,
                isFriend = false,
                isSelf = false,
                isOfficialAccount = false
            ),
            isPopupMode = false,
            onCloseClick = {},
            onMoreClick = {},
            onAvatarClick = {},
            onOriginalAvatarClick = {},
            onEditClick = {},
            onMessageClick = {},
            onCallClick = {},
            onShareClick = {},
            onAddFriendClick = {},
            onCommonGroupsClick = {},
            onCopyUserId = {}
        )
    }
}

@Preview(showBackground = true, name = "Weak-Pending Contact")
@Composable
private fun ContactDetailScreenWeakPendingPreview() {
    DifftTheme {
        ContactDetailScreen(
            uiState = ContactDetailUiState(
                displayName = "Removing Soon",
                userId = "removing_id",
                joinedAt = "2024-03-20",
                commonGroupsCount = 0,
                isFriend = false,
                isWeakPending = true,
                isSelf = false,
                isOfficialAccount = false
            ),
            isPopupMode = false,
            onCloseClick = {},
            onMoreClick = {},
            onAvatarClick = {},
            onOriginalAvatarClick = {},
            onEditClick = {},
            onMessageClick = {},
            onCallClick = {},
            onShareClick = {},
            onAddFriendClick = {},
            onCommonGroupsClick = {},
            onCopyUserId = {}
        )
    }
}

@Preview(showBackground = true, name = "Popup Mode")
@Composable
private fun ContactDetailScreenPopupPreview() {
    DifftTheme {
        ContactDetailScreen(
            uiState = ContactDetailUiState(
                displayName = "Jane Smith",
                userId = "jane_smith",
                joinedAt = "2024-02-10",
                commonGroupsCount = 5,
                isFriend = true,
                isSelf = false,
                isOfficialAccount = false
            ),
            isPopupMode = true,
            onCloseClick = {},
            onMoreClick = {},
            onAvatarClick = {},
            onOriginalAvatarClick = {},
            onEditClick = {},
            onMessageClick = {},
            onCallClick = {},
            onShareClick = {},
            onAddFriendClick = {},
            onCommonGroupsClick = {},
            onCopyUserId = {}
        )
    }
}