package com.difft.android.chat.contacts.contactsdetail

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import org.difft.app.database.models.ContactorModel

/**
 * Contact detail UI state
 */
data class ContactDetailUiState(
    val contactor: ContactorModel? = null,
    val isFriend: Boolean = true,
    val isSelf: Boolean = false,
    val isBot: Boolean = false,
    val isOfficialBot: Boolean = false,
    val displayName: String = "",
    val originalName: String? = null,
    val hasRemark: Boolean = false,
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
@Composable
fun ContactDetailScreen(
    uiState: ContactDetailUiState,
    isPopupMode: Boolean,
    showBackButton: Boolean = true,
    onCloseClick: () -> Unit,
    onMoreClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onEditClick: () -> Unit,
    onMessageClick: () -> Unit,
    onCallClick: () -> Unit,
    onShareClick: () -> Unit,
    onAddFriendClick: () -> Unit,
    onCommonGroupsClick: () -> Unit,
    onCopyUserId: () -> Unit,
    onWebsiteClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Popup mode: calculate minimum height (40% of screen height) to avoid looking too short
    val minHeight = if (isPopupMode) {
        (LocalConfiguration.current.screenHeightDp * 0.4f).dp
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
            .background(DifftTheme.colors.backgroundSecondary)
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
            isOfficialBot = uiState.isOfficialBot,
            onAvatarClick = onAvatarClick,
            modifier = Modifier.padding(horizontal = DifftTheme.spacing.insetLarge)
        )

        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackMedium))

        // Action buttons (with horizontal padding)
        ActionButtonsSection(
            isSelf = uiState.isSelf,
            isFriend = uiState.isFriend,
            isBot = uiState.isBot,
            onMessageClick = onMessageClick,
            onCallClick = onCallClick,
            onShareClick = onShareClick,
            onAddFriendClick = onAddFriendClick,
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
            isBot = uiState.isBot,
            isOfficialBot = uiState.isOfficialBot,
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
    isOfficialBot: Boolean,
    onAvatarClick: () -> Unit,
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
                val context = LocalContext.current
                val avatarSizePx = with(LocalDensity.current) {
                    DifftTheme.spacing.avatarLarge.roundToPx()
                }
                AndroidView(
                    factory = { ctx -> AvatarView(ctx) },
                    update = { avatarView ->
                        // Pass explicit size to avoid layout timing issues in Compose AndroidView
                        avatarView.setAvatar(contactor, 22, avatarSizePx)
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

                if (isOfficialBot) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.chat_ic_official_bot_badge),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.Unspecified
                    )
                }
            }

            // Subtitle line:
            // Bot + remark: "Official Account・Name: xxx"
            // Bot without remark: "Official Account"
            // Normal + remark: "Name: xxx"
            val subtitle = when {
                isOfficialBot && hasRemark && !originalName.isNullOrEmpty() -> {
                    stringResource(R.string.contact_official_account_label) +
                            "・" +
                            stringResource(R.string.contact_name_label, originalName)
                }
                isOfficialBot -> stringResource(R.string.contact_official_account_label)
                hasRemark && !originalName.isNullOrEmpty() -> {
                    stringResource(R.string.contact_name_label, originalName)
                }
                else -> null
            }

            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp
                    ),
                    color = DifftTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ActionButtonsSection(
    isSelf: Boolean,
    isFriend: Boolean,
    isBot: Boolean,
    onMessageClick: () -> Unit,
    onCallClick: () -> Unit,
    onShareClick: () -> Unit,
    onAddFriendClick: () -> Unit,
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
                if (!isBot) {
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
                // Non-friend: show only "Add Contact" button, which adds friend then navigates to chat
                ActionButton(
                    iconRes = R.drawable.chat_icon_add_contact,
                    label = stringResource(R.string.contact_add_contacts),
                    onClick = onAddFriendClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(DifftTheme.spacing.inlineSmall))
            .background(DifftTheme.colors.backgroundSettingItem)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            modifier = Modifier.size(DifftTheme.spacing.iconMedium),
            tint = DifftTheme.colors.icon
        )
        Spacer(modifier = Modifier.height(DifftTheme.spacing.stackSmall))
        Text(
            text = label,
            style = DifftTheme.typography.labelMedium,
            color = DifftTheme.colors.textPrimary
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
    isBot: Boolean,
    isOfficialBot: Boolean,
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
            .background(DifftTheme.colors.backgroundSettingItem)
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
        if (!isSelf && !isBot) {
            Spacer(modifier = Modifier.height(16.dp))
            ContactInfoRow(
                label = stringResource(R.string.chat_group_in_common),
                value = commonGroupsCount.toString(),
                showArrow = commonGroupsCount > 0,
                onClick = if (commonGroupsCount > 0) onCommonGroupsClick else null
            )
        }

        // Website (only for official bot)
        if (isOfficialBot && !website.isNullOrEmpty()) {
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
                isBot = false
            ),
            isPopupMode = false,
            onCloseClick = {},
            onMoreClick = {},
            onAvatarClick = {},
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
                isBot = false
            ),
            isPopupMode = false,
            onCloseClick = {},
            onMoreClick = {},
            onAvatarClick = {},
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
                isBot = false
            ),
            isPopupMode = false,
            onCloseClick = {},
            onMoreClick = {},
            onAvatarClick = {},
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
                isBot = false
            ),
            isPopupMode = true,
            onCloseClick = {},
            onMoreClick = {},
            onAvatarClick = {},
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