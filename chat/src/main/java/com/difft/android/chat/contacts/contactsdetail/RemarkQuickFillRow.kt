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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R

/**
 * Edit-mode shortcut under the remark input: the contact's real name plus an arrow, both tappable,
 * appending that name to whatever is already typed. Whether it appears is decided once, when edit
 * mode opens ([shouldOfferQuickFill]); tapping it hides it for the rest of that edit.
 */
@Composable
internal fun RemarkQuickFillRow(
    originalName: String,
    onFill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // Role.Button so TalkBack announces the name as actionable, not as a plain label.
        modifier = modifier.clickable(role = Role.Button) { onFill() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = originalName,
            style = nameStyle,
            color = DifftTheme.colors.textInfo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(GAP))
        Icon(
            painter = painterResource(R.drawable.chat_ic_arrow_up_left),
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
            tint = DifftTheme.colors.textInfo
        )
    }
}

/**
 * Decided once, as edit mode opens: offered when the contact has a real name and the initial remark
 * is not exactly that name. The comparison is a case-sensitive equality check on the trimmed values —
 * deliberately not "contains" or any fuzzy match, so `alice` still gets the shortcut for `Alice`.
 * Typing afterwards never brings it back or takes it away; only re-entering edit mode re-decides.
 */
internal fun shouldOfferQuickFill(originalName: String, initialRemark: String): Boolean =
    originalName.isNotBlank() && initialRemark.trim() != originalName.trim()

/**
 * Appends the name to the draft: an empty draft becomes the name, otherwise the draft's trailing
 * spaces are dropped and the name is joined with a single space. A draft that already equals the
 * name is left untouched — the row still disappears, it just has nothing to add.
 */
internal fun appendQuickFill(draft: String, originalName: String): String {
    val trimmedDraft = draft.trimEnd()
    return when {
        trimmedDraft.trim() == originalName.trim() -> draft
        trimmedDraft.isEmpty() -> originalName
        else -> "$trimmedDraft $originalName"
    }
}

private val nameStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
private val GAP = 4.dp
private val ICON_SIZE = 14.dp
