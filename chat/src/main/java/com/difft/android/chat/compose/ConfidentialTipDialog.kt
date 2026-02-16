package com.difft.android.chat.compose

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            painter = painterResource(id = R.drawable.chat_btn_confidential_mode),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            colorFilter = ColorFilter.tint(colorResource(id = com.difft.android.base.R.color.t_info))
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Title
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colorResource(id = com.difft.android.base.R.color.t_primary),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Content
        Text(
            text = content,
            fontSize = 14.sp,
            color = colorResource(id = com.difft.android.base.R.color.t_secondary),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // OK Button with primary color
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(id = com.difft.android.base.R.color.primary)
            )
        ) {
            Text(
                text = "OK",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
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