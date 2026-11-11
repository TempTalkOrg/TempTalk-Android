package com.difft.android.chat.contacts.contactsdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R






@Composable
internal fun RemarkQuickFillRow(
    originalName: String,
    onFill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        
        modifier = modifier.clickable(role = Role.Button) { onFill() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = originalName,
            style = DifftTheme.typography.h7,
            color = DifftTheme.colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(GAP))
        Icon(
            painter = painterResource(R.drawable.chat_ic_arrow_up_left),
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
            tint = DifftTheme.colors.primary
        )
    }
}







internal fun shouldOfferQuickFill(originalName: String, initialRemark: String): Boolean =
    originalName.isNotBlank() && initialRemark.trim() != originalName.trim()






internal fun appendQuickFill(draft: String, originalName: String): String {
    val trimmedDraft = draft.trimEnd()
    return when {
        trimmedDraft.trim() == originalName.trim() -> draft
        trimmedDraft.isEmpty() -> originalName
        else -> "$trimmedDraft $originalName"
    }
}

private val GAP = 4.dp
private val ICON_SIZE = 14.dp
