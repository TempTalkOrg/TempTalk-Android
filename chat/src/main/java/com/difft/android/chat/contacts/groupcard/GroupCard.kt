package com.difft.android.chat.contacts.groupcard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.contacts.compose.CardAction
import com.difft.android.chat.contacts.compose.CardActions
import com.difft.android.chat.contacts.compose.CardBanner
import com.difft.android.chat.contacts.compose.CardDivider
import com.difft.android.chat.contacts.compose.CardInfoRow
import com.difft.android.chat.contacts.compose.GroupAvatarWithCount
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Action
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Intent
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.State
import com.difft.android.chat.setting.archive.toCompactExpiryText
import com.difft.android.base.ui.compose.floatingSurface
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun GroupCard(state: State, onIntent: (Intent) -> Unit, modifier: Modifier = Modifier, floating: Boolean = false) {
    val shape = RoundedCornerShape(DifftTheme.contacts.cardRadius)
    
    val surface = if (floating) Modifier.floatingSurface(shape, fill = DifftTheme.colors.backgroundPopup)
        else Modifier.clip(shape).background(DifftTheme.colors.backgroundPopup)
    Box(modifier.fillMaxWidth().then(surface)) {
        GroupCardBody(state, onIntent)
    }
}

@Composable
private fun GroupCardBody(state: State, onIntent: (Intent) -> Unit) {
    val tokens = DifftTheme.contacts
    Column(
        modifier = Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = tokens.cardSidePadding, vertical = tokens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(tokens.gapSection),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tokens.gapSection)) {
            state.group?.let { group ->
                GroupAvatarWithCount(group, tokens.groupCardAvatar, showCount = false, memberCount = state.memberCount)
            }
            Text(state.name.ifBlank { stringResource(R.string.group_unknown_group) },
                style = DifftTheme.typography.h4, color = DifftTheme.colors.textPrimary, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        state.dissolveDays?.let { days ->
            CardBanner(
                title = if (days == 0) stringResource(R.string.group_dissolve_today)
                    else pluralStringResource(R.plurals.group_dissolve_in_days, days, days),
                hint = stringResource(R.string.group_dissolve_keep_hint),
            )
        }
        CardDivider()
        Text(stringResource(R.string.contacts_card_group_info), style = DifftTheme.typography.h5, color = DifftTheme.colors.textPrimary)
        Column {
            if (state.messageExpirySeconds > 0) {
                CardInfoRow(stringResource(R.string.contacts_card_countdown),
                    state.messageExpirySeconds.toCompactExpiryText(LocalContext.current))
            }
            CardInfoRow(stringResource(R.string.contacts_card_group_members), state.memberCount.toString(),
                onClick = if (state.actionsEnabled) ({ onIntent(Intent.Open(Action.MEMBERS)) }) else null)
        }
        CardDivider()
        CardActions(listOf(
            CardAction(stringResource(R.string.contact_action_message), com.difft.android.base.R.drawable.base_tabler_message_circle,
                enabled = state.actionsEnabled, onClick = { onIntent(Intent.Open(Action.MESSAGE)) }),
            CardAction(stringResource(R.string.chat_action_call), R.drawable.chat_contact_detail_ic_call,
                enabled = state.actionsEnabled, onClick = { onIntent(Intent.Open(Action.CALL)) }),
            CardAction(stringResource(R.string.contacts_card_invite), R.drawable.chat_tabler_user_plus,
                enabled = state.actionsEnabled, onClick = { onIntent(Intent.Open(Action.INVITE)) }),
            CardAction(stringResource(R.string.chat_action_more), R.drawable.chat_message_action_more,
                enabled = state.actionsEnabled, onClick = { onIntent(Intent.Open(Action.MORE)) }),
        ))
    }
}
