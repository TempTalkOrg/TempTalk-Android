package com.difft.android.chat.contacts.contactsdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.compose.input.DifftTextFieldStyle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.common.compose.AvatarEditOverlay
import com.difft.android.chat.common.compose.ContactAvatar
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditViewModel
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import org.difft.app.database.models.ContactorModel

/**
 * Avatar + name block of the contact card.
 *
 * Browse: avatar (tap → preview), display name, a persistent pencil after the name (remark edit
 * entry, hidden for self) and the original-name / original-avatar subtitle when a remark is set.
 * Edit: avatar wears [AvatarEditOverlay] (tap → change remark avatar), the name becomes a bordered
 * 40dp input holding the remark, and [RemarkQuickFillRow] sits under it while the contact's real
 * name still adds something. The privacy line lives in the top bar, not here.
 */
@Composable
internal fun ContactDetailHeader(
    contactor: ContactorModel?,
    displayName: String,
    originalName: String?,
    hasRemark: Boolean,
    hasRemarkAvatar: Boolean,
    originalAvatarJson: String?,
    isOfficialAccount: Boolean,
    isEditing: Boolean,
    editingName: String,
    showEditEntry: Boolean,
    quickFillName: String,
    showQuickFill: Boolean,
    onAvatarClick: () -> Unit,
    onOriginalAvatarClick: () -> Unit,
    onEditClick: () -> Unit,
    onEditingNameChange: (String) -> Unit,
    onSubmitEdit: () -> Unit,
    onAvatarClickInEdit: () -> Unit,
    onQuickFill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(DifftTheme.spacing.avatarLarge)
                .clip(CircleShape)
                .clickable { if (isEditing) onAvatarClickInEdit() else onAvatarClick() }
        ) {
            contactor?.let { contact ->
                ContactAvatar(
                    contactor = contact,
                    size = DifftTheme.spacing.avatarLarge,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (isEditing) {
                AvatarEditOverlay(modifier = Modifier.fillMaxSize())
            }
        }

        Spacer(modifier = Modifier.width(AVATAR_NAME_GAP))

        if (isEditing) {
            Column(modifier = Modifier.weight(1f)) {
                RemarkNameInput(
                    value = editingName,
                    onValueChange = onEditingNameChange,
                    onDone = onSubmitEdit,
                )
                if (showQuickFill) {
                    Spacer(modifier = Modifier.height(QUICK_FILL_TOP_GAP))
                    RemarkQuickFillRow(originalName = quickFillName, onFill = onQuickFill)
                }
            }
        } else {
            NameColumn(
                contactor = contactor,
                displayName = displayName,
                originalName = originalName,
                hasRemark = hasRemark,
                hasRemarkAvatar = hasRemarkAvatar,
                originalAvatarJson = originalAvatarJson,
                isOfficialAccount = isOfficialAccount,
                showEditEntry = showEditEntry,
                onEditClick = onEditClick,
                onOriginalAvatarClick = onOriginalAvatarClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NameColumn(
    contactor: ContactorModel?,
    displayName: String,
    originalName: String?,
    hasRemark: Boolean,
    hasRemarkAvatar: Boolean,
    originalAvatarJson: String?,
    isOfficialAccount: Boolean,
    showEditEntry: Boolean,
    onEditClick: () -> Unit,
    onOriginalAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayName,
                style = DifftTheme.typography.h4,
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

            if (showEditEntry) {
                Spacer(modifier = Modifier.width(PENCIL_GAP))
                Icon(
                    painter = painterResource(id = R.drawable.chat_ic_pencil),
                    contentDescription = null,
                    modifier = Modifier
                        .clickable { onEditClick() }
                        .padding(PENCIL_TOUCH_PADDING)
                        .size(PENCIL_SIZE),
                    tint = DifftTheme.colors.textTertiary
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

/**
 * The library form field holding the remark (empty when there is none), with a trailing clear
 * button. 16sp Regular per spec — a notch below the browse-mode name.
 */
@Composable
private fun RemarkNameInput(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DifftClearableTextField(
        value = value,
        onValueChange = onValueChange,
        onClear = { onValueChange("") },
        style = DifftTextFieldStyle.Form,
        clearMode = ClearMode.WhenNotEmpty,
        hint = stringResource(R.string.contact_remark_name_placeholder),
        textStyle = DifftTheme.typography.p2,
        maxLength = ContactRemarkEditViewModel.MAX_REMARK_LENGTH,
        autoFocus = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onImeAction = onDone,
        modifier = modifier.fillMaxWidth()
    )
}

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
                style = DifftTheme.typography.p3,
                color = tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if ((hasRemark && !originalName.isNullOrEmpty()) || hasRemarkAvatar) {
                Text(text = "・", style = DifftTheme.typography.p3, color = tertiary)
            }
        }
        when {
            // Both remarked: inline "[smallAvatar] originalName" without key labels.
            hasRemark && hasRemarkAvatar && !originalName.isNullOrEmpty() -> {
                SmallOriginalAvatar(originalAvatarJson, contactor, onOriginalAvatarClick)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = originalName,
                    style = DifftTheme.typography.p3,
                    color = tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Only name remarked: "Name: originalName"
            hasRemark && !originalName.isNullOrEmpty() -> {
                Text(
                    text = stringResource(R.string.contact_name_label, originalName),
                    style = DifftTheme.typography.p3,
                    color = tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Only avatar remarked: "Original: [smallAvatar]"
            hasRemarkAvatar -> {
                Text(
                    text = stringResource(R.string.contact_avatar_label),
                    style = DifftTheme.typography.p3,
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


private val AVATAR_NAME_GAP = 12.dp
private val PENCIL_GAP = 8.dp
// Spec says 16dp; Tabler's 2px stroke reads too thin at that size, so 20dp.
private val PENCIL_SIZE = 20.dp
private val PENCIL_TOUCH_PADDING = 8.dp
private val QUICK_FILL_TOP_GAP = 4.dp
