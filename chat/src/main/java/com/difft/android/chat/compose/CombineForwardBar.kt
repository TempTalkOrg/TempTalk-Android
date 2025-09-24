package com.difft.android.chat.compose

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.chat.R


@Preview(
    name = "Light Theme", showBackground = true,
    backgroundColor = android.graphics.Color.WHITE.toLong(),
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Preview(
    name = "Dark Theme",
    showBackground = true,
    backgroundColor = android.graphics.Color.BLACK.toLong(),
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    locale = "zh",
    fontScale = 2.0f
)
@Composable
fun CombineForwardBar(
    @PreviewParameter(StateSelectMessageModelDataProvider::class) stateData: SelectMessageState,
    onForwardClick: () -> Unit = {},
    onCombineClick: () -> Unit = {},
    onSaveClick: () -> Unit = {}
) {
    val state:SelectMessageState  = stateData
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorResource(com.difft.android.base.R.color.bg2)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorResource(com.difft.android.base.R.color.bg3))
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = "${state.selectedMessageIds.size}/${state.totalMessageCount} ${stringResource(R.string.chat_message_input_hint)}",
                fontSize = 12.sp,
                color = colorResource(com.difft.android.base.R.color.t_primary),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Icons row with weights to distribute space evenly
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            // Forward Icon
            IconWithText(
                iconPainter = painterResource(R.drawable.ic_chat_pinned_manage_forward),
                text = stringResource(R.string.chat_message_action_forward),
                isEnabled = state.selectedMessageIds.isNotEmpty(),
                onClick = onForwardClick,
                modifier = Modifier.weight(1f)  // Use weight to distribute space
            )

            // Combine & Forward Icon
            IconWithText(
                iconPainter = painterResource(R.drawable.chat_message_action_select_multiple),
                text = stringResource(R.string.chat_message_action_combine_forward),
                isEnabled = state.selectedMessageIds.size > 1,
                onClick = onCombineClick,
                modifier = Modifier.weight(1f)
            )

            // Save Icon
            IconWithText(
                iconPainter = painterResource(R.drawable.ic_chat_pinned_manage_save_notes),
                text = stringResource(R.string.chat_message_action_save_to_note),
                isEnabled = state.selectedMessageIds.isNotEmpty(),
                onClick = onSaveClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun IconWithText(
    iconPainter: Painter,
    text: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier  // Pass modifier for flexibility
) {
    val tintColor = if (isEnabled) {
        colorResource(id = com.difft.android.base.R.color.t_primary)
    } else {
        colorResource(id = com.difft.android.base.R.color.t_disable)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(enabled = isEnabled) { onClick() }
            .padding(horizontal = 8.dp)  // Add padding to avoid text overflow
    ) {
        Image(
            painter = iconPainter,
            contentDescription = text,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(tintColor)
        )
        Text(
            text = text,
            maxLines = 1,  // Ensure text doesn't overflow
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = tintColor
        )
    }
}

data class SelectMessageState(
    val editModel: Boolean,
    val selectedMessageIds: Set<String>,
    val totalMessageCount: Int
)