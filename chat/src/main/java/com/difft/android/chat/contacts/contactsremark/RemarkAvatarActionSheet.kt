package com.difft.android.chat.contacts.contactsremark

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R

/**
 * Bottom sheet shown when the user taps the avatar while editing the remark on the contact card
 * AND there is already a remark avatar set. Three rows: choose new photo,
 * restore (clear) the remark avatar, cancel. The first two are blue (t.info)
 * because they're affirmative actions; cancel is the default text color.
 *
 * When no remark avatar is set, the avatar tap goes straight to the picker —
 * this sheet is not shown in that case.
 */
@Composable
fun RemarkAvatarActionSheet(
    onChoosePhotos: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
    ) {
        ActionSheetRow(
            text = stringResource(R.string.contact_remark_avatar_choose_photos),
            color = DifftTheme.colors.textInfo,
            onClick = onChoosePhotos,
        )
        ActionSheetRow(
            text = stringResource(R.string.contact_remark_avatar_restore),
            color = DifftTheme.colors.textInfo,
            onClick = onRestore,
        )
        ActionSheetRow(
            text = stringResource(R.string.group_leave_cancel),
            color = DifftTheme.colors.textPrimary,
            onClick = onCancel,
        )
    }
}

@Composable
private fun ActionSheetRow(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = color,
        style = DifftTheme.typography.p2,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
    )
}
