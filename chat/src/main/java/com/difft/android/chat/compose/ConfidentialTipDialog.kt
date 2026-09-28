package com.difft.android.chat.compose

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R

/**
 * Confidential message first-use tip dialog content.
 * Shows an icon, title, description and OK button.
 */
@Composable
fun ConfidentialTipDialogContent(
    title: String,
    content: String,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon with t_info color
        Image(
            painter = painterResource(id = R.drawable.chat_btn_confidential_mode_enable),
            contentDescription = null,
            modifier = Modifier.size(32.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Title
        Text(
            text = title,
            style = DifftTheme.typography.h5,
            color = DifftTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Content
        Text(
            text = content,
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        DifftButton(
            text = stringResource(id = R.string.chat_dialog_ok),
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(
    name = "Light Theme",
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Preview(
    name = "Dark Theme",
    showBackground = true,
    backgroundColor = 0xFF181A20,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun ConfidentialTipDialogContentPreview() {
    ConfidentialTipDialogContent(
        title = "Confidential Messages",
        content = "Read once, then gone.",
        onConfirm = {}
    )
}