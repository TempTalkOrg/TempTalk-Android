package com.difft.android.chat.common.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R

enum class IdentityHeaderMode { Browse, Edit }

/**
 * The secondary line under the name (group page only): encryption status / upgrade entry.
 * [caution] switches the whole row to the orange pending-action palette.
 */
data class IdentityMeta(
    val text: String,
    @DrawableRes val iconRes: Int,
    val caution: Boolean,
    val onClick: () -> Unit,
)

/**
 * Centered identity block shared by the group-settings and 1:1-settings pages: a 64dp avatar,
 * the name (2 lines max) and an optional meta row, sitting directly on the page background.
 *
 * Edit mode (group page): the avatar gets [AvatarEditOverlay] when [avatarEditable]; when
 * [nameEditable] the name leaves the centered block and becomes a full-width card with a plain
 * input (no clear button — the top bar's "Done" is the only commit control). The meta row is
 * hidden while editing.
 *
 * [onAvatarBottomChanged] reports the avatar's bottom edge in window coordinates so the host can
 * drive [CollapsingTitleBar]'s collapsed state from its own scroll container.
 */
@Composable
fun IdentityHeader(
    name: String,
    mode: IdentityHeaderMode,
    avatar: @Composable (Modifier) -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
    editingName: String = "",
    onEditingNameChange: (String) -> Unit = {},
    onEditingNameDone: () -> Unit = {},
    nameMaxLength: Int? = null,
    avatarEditable: Boolean = false,
    nameEditable: Boolean = false,
    meta: IdentityMeta? = null,
    onHeaderClick: (() -> Unit)? = null,
    onAvatarBottomChanged: ((Float) -> Unit)? = null,
) {
    val editing = mode == IdentityHeaderMode.Edit
    val nameInCard = editing && nameEditable

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onHeaderClick != null) Modifier.clickable { onHeaderClick() } else Modifier)
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(DifftTheme.spacing.avatarLarge)
                .clickable { onAvatarClick() }
                .onGloballyPositioned { coords ->
                    onAvatarBottomChanged?.invoke(coords.boundsInWindow().bottom)
                }
        ) {
            avatar(Modifier.fillMaxSize())
            if (editing && avatarEditable) {
                AvatarEditOverlay(modifier = Modifier.fillMaxSize())
            }
        }

        if (!nameInCard) {
            Spacer(modifier = Modifier.height(NAME_TOP_GAP))
            Text(
                text = name,
                style = nameStyle,
                color = DifftTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = NAME_MAX_WIDTH)
                    .padding(horizontal = HORIZONTAL_INSET)
            )
        }

        AnimatedVisibility(visible = !editing && meta != null) {
            meta?.let { MetaRow(it) }
        }

        if (nameInCard) {
            Spacer(modifier = Modifier.height(CARD_TOP_GAP))
            NameEditCard(
                value = editingName,
                onValueChange = onEditingNameChange,
                onDone = onEditingNameDone,
                maxLength = nameMaxLength,
            )
        }
        // Same bottom inset in both modes so the card below never touches the header content.
        Spacer(modifier = Modifier.height(BOTTOM_INSET))
    }
}

@Composable
private fun MetaRow(meta: IdentityMeta) {
    val color = if (meta.caution) DifftTheme.colors.textCaution else DifftTheme.colors.textTertiary
    Row(
        modifier = Modifier
            .padding(top = META_TOP_GAP)
            .clickable { meta.onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(meta.iconRes),
            contentDescription = null,
            modifier = Modifier.size(META_ICON_SIZE),
            tint = color
        )
        Spacer(modifier = Modifier.width(META_GAP))
        Text(
            text = meta.text,
            style = metaStyle,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(META_GAP))
        Icon(
            painter = painterResource(R.drawable.chat_ic_arrow_right),
            contentDescription = null,
            modifier = Modifier.size(META_CHEVRON_SIZE),
            tint = color
        )
    }
}

/**
 * Full-width white card holding only the name input: same radius as the setting cards below,
 * text left-aligned, cursor at the end, keyboard raised on entry. No buttons inside on purpose.
 */
@Composable
private fun NameEditCard(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    maxLength: Int?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DifftTheme.spacing.insetLarge)
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(DifftTheme.colors.bgElevated)
    ) {
        DifftClearableTextField(
            value = value,
            onValueChange = onValueChange,
            onClear = {},
            clearMode = ClearMode.Never,
            containerColor = DifftTheme.colors.bgElevated,
            textStyle = cardInputStyle,
            height = CARD_HEIGHT,
            contentPadding = PaddingValues(horizontal = CARD_INSET),
            maxLength = maxLength,
            autoFocus = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onImeAction = onDone,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private val nameStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
private val metaStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
private val cardInputStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)

private val HORIZONTAL_INSET = 20.dp
private val BOTTOM_INSET = 18.dp
private val NAME_TOP_GAP = 10.dp
private val NAME_MAX_WIDTH = 300.dp
private val META_TOP_GAP = 4.dp
private val META_GAP = 5.dp
private val META_ICON_SIZE = 14.dp
private val META_CHEVRON_SIZE = 13.dp
private val CARD_TOP_GAP = 18.dp
private val CARD_HEIGHT = 52.dp
private val CARD_RADIUS = 12.dp
private val CARD_INSET = 16.dp
