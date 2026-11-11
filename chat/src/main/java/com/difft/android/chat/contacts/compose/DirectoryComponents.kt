package com.difft.android.chat.contacts.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.common.compose.ContactAvatar
import com.difft.android.chat.setting.archive.toCompactExpiryText
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel

enum class DirectoryTab { Groups, Contacts }

@Composable
fun DirectoryTabBand(selected: DirectoryTab, onSelect: (DirectoryTab) -> Unit, modifier: Modifier = Modifier) {
    val tokens = DifftTheme.contacts
    Row(
        
        modifier.fillMaxWidth().padding(tokens.tabBandPadding),
        horizontalArrangement = Arrangement.spacedBy(tokens.gapMedium),
    ) {
        DirectoryTab.entries.forEach { tab ->
            val isSelected = selected == tab
            Surface(
                modifier = Modifier.height(tokens.tabHeight).semantics { this.selected = isSelected },
                shape = CircleShape,
                color = if (isSelected) DifftTheme.colors.textPrimary else Color.Transparent,
                border = if (isSelected) null else BorderStroke(tokens.tabBorder, DifftTheme.colors.line),
                onClick = { onSelect(tab) },
            ) {
                Box(Modifier.padding(horizontal = tokens.tabHorizontalPadding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (tab == DirectoryTab.Groups) R.string.contact_groups else R.string.contacts_directory_contacts),
                        style = DifftTheme.typography.h6,
                        color = if (isSelected) DifftTheme.colors.background else DifftTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
fun DirectorySectionHeader(letter: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(DifftTheme.contacts.sectionHeight)
            .padding(horizontal = DifftTheme.contacts.cardPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(letter, style = DifftTheme.contacts.section, color = DifftTheme.colors.textSecondary)
    }
}

@Composable
fun DirectoryRow(
    contact: ContactorModel,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    messageExpirySeconds: Long? = null,
    official: Boolean = false,
    selected: Boolean = false,
    isLastInSection: Boolean = false,
    self: Boolean = false,
    largerText: Boolean = false,
    endInset: Dp = DifftTheme.contacts.rowEndInset,
) {
    DirectoryRowContent(name, messageExpirySeconds, official, selected, isLastInSection, largerText, endInset, onClick, modifier) {
        if (self) FavoritesAvatar(DifftTheme.contacts.rowAvatar) else ContactAvatar(contact, DifftTheme.contacts.rowAvatar)
    }
}

@Composable
fun DirectoryRow(
    group: GroupModel,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    memberCount: Int = 0,
    messageExpirySeconds: Long? = group.messageExpiry?.toLong(),
    selected: Boolean = false,
    isLastInSection: Boolean = false,
    largerText: Boolean = false,
    endInset: Dp = DifftTheme.contacts.rowEndInset,
) {
    DirectoryRowContent(name, messageExpirySeconds, false, selected, isLastInSection, largerText, endInset, onClick, modifier) {
        GroupAvatarWithCount(group, memberCount = memberCount)
    }
}

@Composable
private fun DirectoryRowContent(
    name: String,
    messageExpirySeconds: Long?,
    official: Boolean,
    selected: Boolean,
    isLastInSection: Boolean,
    largerText: Boolean,
    endInset: Dp,
    onClick: () -> Unit,
    modifier: Modifier,
    avatar: @Composable () -> Unit,
) {
    val tokens = DifftTheme.contacts
    val expiry = (messageExpirySeconds ?: 0L).toCompactExpiryText(LocalContext.current)
    Box(
        modifier.fillMaxWidth()
            .background(if (selected) DifftTheme.colors.backgroundTertiary else DifftTheme.colors.background)
            .semantics { this.selected = selected }
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = tokens.rowHeight)
                .padding(start = tokens.cardPadding, end = endInset, top = tokens.gapMedium, bottom = tokens.gapMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            avatar()
            Spacer(Modifier.width(tokens.rowTextStart - tokens.cardPadding - tokens.rowAvatar))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(tokens.gapSmall)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f, fill = false), style = if (largerText) DifftTheme.typography.p0 else DifftTheme.typography.p2, color = DifftTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (official) {
                        Spacer(Modifier.width(tokens.gapSmall))
                        Text(stringResource(R.string.contacts_official), style = DifftTheme.typography.p4, color = DifftTheme.colors.textTertiary, maxLines = 1)
                    }
                }
                if (expiry.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tokens.gapSmall)) {
                        Icon(painterResource(R.drawable.chat_ic_hourglass), contentDescription = null, modifier = Modifier.size(tokens.rowTimerIcon), tint = DifftTheme.colors.textTertiary)
                        Text(expiry, style = DifftTheme.typography.p4, color = DifftTheme.colors.textTertiary)
                    }
                }
            }
        }
        if (!isLastInSection) {
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = tokens.rowTextStart, end = endInset),
                thickness = tokens.divider,
                color = DifftTheme.colors.divider,
            )
        }
    }
}
