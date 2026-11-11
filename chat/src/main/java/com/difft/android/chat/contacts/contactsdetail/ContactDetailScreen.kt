package com.difft.android.chat.contacts.contactsdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.difft.android.base.ui.compose.FloatingIconButton
import com.difft.android.base.ui.compose.floatingSurface
import com.difft.android.base.ui.compose.FloatingTextButton
import com.difft.android.base.ui.sidechrome.PublishSideChrome
import com.difft.android.base.ui.sidechrome.SideChromeModel
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.contacts.compose.CardAction
import com.difft.android.chat.contacts.compose.CardActions
import com.difft.android.chat.contacts.compose.CardBanner
import com.difft.android.chat.contacts.compose.CardDivider
import com.difft.android.chat.contacts.compose.CardInfoRow
import com.difft.android.chat.setting.archive.toCompactExpiryText


@Composable
fun ContactCard(
    uiState: ContactDetailUiState,
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
    onWebsiteClick: () -> Unit = {},
    onRemoveNowClick: () -> Unit = {},
    onEditingRemarkNameChange: (String) -> Unit = {},
    onSubmitRemark: () -> Unit = {},
    onAvatarClickInEdit: () -> Unit = {},
    onQuickFillRemark: () -> Unit = {},
    floating: Boolean = false,
) {
    val shape = RoundedCornerShape(DifftTheme.contacts.cardRadius)
    
    val surface = if (floating) Modifier.floatingSurface(shape, fill = DifftTheme.colors.backgroundPopup)
        else Modifier.clip(shape).background(DifftTheme.colors.backgroundPopup)
    Box(modifier.fillMaxWidth().then(surface)) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftTheme.contacts.cardSidePadding, vertical = DifftTheme.contacts.cardPadding),
            verticalArrangement = Arrangement.spacedBy(DifftTheme.spacing.stackMedium),
        ) {
            if (uiState.isEditingRemark) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FloatingIconButton(iconRes = R.drawable.ic_close, contentDescription = stringResource(R.string.contact_remove_dialog_cancel), onClick = onCloseClick)
                    Text(stringResource(R.string.contact_remark_private_hint), style = DifftTheme.typography.p3,
                        color = DifftTheme.colors.textTertiary, modifier = Modifier.weight(1f))
                    FloatingTextButton(text = stringResource(R.string.group_edit_name_done), onClick = onSubmitRemark,
                        style = DifftTheme.typography.p2, color = DifftTheme.colors.primary)
                }
            }
            ContactDetailHeader(
                contactor = uiState.contactor,
                displayName = uiState.displayName,
                originalName = uiState.originalName,
                hasRemark = uiState.hasRemark,
                hasRemarkAvatar = uiState.hasRemarkAvatar,
                originalAvatarJson = uiState.originalAvatarJson,
                isOfficialAccount = uiState.isOfficialAccount,
                isEditing = uiState.isEditingRemark,
                editingName = uiState.editingRemarkName,
                showEditEntry = uiState.presentation.showRemarkEdit && uiState.contactor != null,
                quickFillName = uiState.quickFillName,
                showQuickFill = uiState.showQuickFill,
                onAvatarClick = onAvatarClick,
                onOriginalAvatarClick = onOriginalAvatarClick,
                onEditClick = onEditClick,
                onEditingNameChange = onEditingRemarkNameChange,
                onSubmitEdit = onSubmitRemark,
                onAvatarClickInEdit = onAvatarClickInEdit,
                onQuickFill = onQuickFillRemark,
            )
            if (!uiState.isEditingRemark) {
                if (uiState.presentation.kind == ContactCardKind.PENDING_REMOVAL) {
                    uiState.pendingRemovalDays?.let { days ->
                        CardBanner(
                            title = if (days <= 1) stringResource(R.string.weak_contact_remove_today)
                                else stringResource(R.string.weak_contact_remove_in_days, days),
                            hint = stringResource(R.string.weak_contact_keep_hint),
                        )
                    }
                }
                CardDivider()
                Text(stringResource(R.string.contact_info_title), style = DifftTheme.typography.h5, color = DifftTheme.colors.textPrimary)
                Column {
                    CardInfoRow(stringResource(R.string.contact_name_profile), uiState.userId, onCopyUserId, onLongClick = onCopyUserId, showChevron = false)
                    if (uiState.presentation.showFriendInfo) {
                        uiState.joinedAt?.takeIf { it.isNotEmpty() }?.let {
                            CardInfoRow(stringResource(R.string.contact_join_at), it)
                        }
                        uiState.sourceDescribe?.takeIf { it.isNotEmpty() }?.let {
                            CardInfoRow(stringResource(R.string.contact_how_you_met), it)
                        }
                    }
                    if (!uiState.isSelf && !uiState.isOfficialAccount) {
                        CardInfoRow(stringResource(R.string.chat_group_in_common), uiState.commonGroupsCount.toString(),
                            onCommonGroupsClick.takeIf { uiState.commonGroupsCount > 0 })
                    }
                    if (uiState.presentation.showFriendInfo && uiState.messageExpirySeconds > 0) {
                        CardInfoRow(stringResource(R.string.contacts_card_countdown), uiState.messageExpirySeconds.toCompactExpiryText(LocalContext.current))
                    }
                    if (uiState.isOfficialAccount) uiState.website?.let {
                        CardInfoRow(stringResource(R.string.contact_info_website), it, onWebsiteClick)
                    }
                }
                CardDivider()
                if (uiState.presentation.kind == ContactCardKind.LOADING) {
                    ActionRowPlaceholder()
                } else {
                    val actions = when (uiState.presentation.kind) {
                        ContactCardKind.FRIEND -> listOfNotNull(
                            CardAction(stringResource(R.string.contact_action_message), com.difft.android.base.R.drawable.base_tabler_message_circle, enabled = uiState.actionsEnabled, onClick = onMessageClick),
                            if (uiState.presentation.showCall) CardAction(stringResource(R.string.contact_action_call), R.drawable.chat_contact_detail_ic_call, enabled = uiState.actionsEnabled, onClick = onCallClick) else null,
                            if (uiState.presentation.showShare) CardAction(stringResource(R.string.contact_action_share), R.drawable.chat_ic_group_invite_share, enabled = uiState.actionsEnabled, onClick = onShareClick) else null,
                            if (uiState.presentation.showMore) CardAction(stringResource(R.string.chat_action_more), R.drawable.chat_message_action_more, enabled = uiState.actionsEnabled, onClick = onMoreClick) else null,
                        )
                        ContactCardKind.STRANGER, ContactCardKind.PENDING_REMOVAL -> listOfNotNull(
                            CardAction(stringResource(R.string.contacts_card_add), R.drawable.chat_tabler_user_plus, enabled = uiState.actionsEnabled, onClick = onAddFriendClick),
                            if (uiState.presentation.showDelete) CardAction(stringResource(R.string.contact_remove_dialog_yes), R.drawable.chat_message_action_delete,
                                destructive = true, enabled = uiState.actionsEnabled, onClick = onRemoveNowClick) else null,
                        )
                        ContactCardKind.LOADING -> emptyList()
                    }
                    CardActions(actions)
                }
            }
        }
    }
}


@Composable
private fun ActionRowPlaceholder() {
    Box(Modifier.alpha(0f).clearAndSetSemantics {}) {
        CardActions(listOf(CardAction(stringResource(R.string.contact_action_message),
            com.difft.android.base.R.drawable.base_tabler_message_circle, enabled = false, onClick = {})))
    }
}


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
    onRemoveNowClick: () -> Unit = {},
    onEditingRemarkNameChange: (String) -> Unit = {},
    onSubmitRemark: () -> Unit = {},
    onAvatarClickInEdit: () -> Unit = {},
    onQuickFillRemark: () -> Unit = {},
) {
    val content: @Composable (Modifier) -> Unit = { cardModifier ->
        ContactCard(uiState, onCloseClick, onMoreClick, onAvatarClick, onOriginalAvatarClick, onEditClick,
            onMessageClick, onCallClick, onShareClick, onAddFriendClick, onCommonGroupsClick, onCopyUserId,
            modifier = cardModifier, onWebsiteClick = onWebsiteClick, onRemoveNowClick = onRemoveNowClick,
            onEditingRemarkNameChange = onEditingRemarkNameChange, onSubmitRemark = onSubmitRemark,
            onAvatarClickInEdit = onAvatarClickInEdit, onQuickFillRemark = onQuickFillRemark,
            floating = !isPopupMode)
    }
    if (isPopupMode) {
        content(modifier)
    } else {
        val onRail = PublishSideChrome(SideChromeModel(back = onCloseClick.takeIf { showBackButton }))
        Box(modifier.fillMaxSize().padding(DifftTheme.spacing.insetLarge), contentAlignment = Alignment.Center) {
            content(Modifier.widthIn(max = DifftTheme.contacts.cardMaxWidth))
            if (showBackButton && !onRail) FloatingIconButton(
                iconRes = com.difft.android.base.R.drawable.chative_ic_back,
                contentDescription = stringResource(R.string.contact_remove_dialog_cancel), onClick = onCloseClick,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}
