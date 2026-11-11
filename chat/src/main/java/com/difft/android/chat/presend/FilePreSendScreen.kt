package com.difft.android.chat.presend

import com.difft.android.base.ui.compose.FloatingIconButton
import com.difft.android.base.ui.compose.FloatingIconButtonEdge
import com.difft.android.base.ui.compose.statusBarWithCutoutPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.difft.android.chat.widget.fileTypeIcon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.floatingSurface
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.compose.input.DifftTextFieldStyle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.utf8Substring
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.ui.confidentialInputBorder
import com.difft.android.chat.common.MAX_TEXT_FILE_SIZE
import com.difft.android.chat.common.MESSAGE_INPUT_MAX_LINES

@Composable
internal fun FilePreSendScreen(
    fileName: String,
    fileSize: Long,
    confidentialMode: Int,
    showConfidentialToggle: Boolean,
    onBack: () -> Unit,
    onConfidentialToggle: () -> Unit,
    onSend: (messageBody: String) -> Unit
) {
    var messageBody by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FILE_PRESEND_SCREEN_TAG)
            .background(DifftTheme.colors.background)
            .statusBarWithCutoutPadding()
            .imePadding()
    ) {
        
        Box(modifier = Modifier.height(52.dp), contentAlignment = Alignment.CenterStart) {
            FloatingIconButton(
                iconRes = com.difft.android.base.R.drawable.chative_ic_back,
                contentDescription = stringResource(com.difft.android.base.R.string.side_chrome_back),
                onClick = onBack,
                modifier = Modifier.padding(start = FloatingIconButtonEdge),
            )
        }

        FilePreSendPreview(fileName, fileSize, Modifier.fillMaxWidth().weight(1f))
        FilePreSendInputBar(
            messageBody = messageBody,
            onMessageChange = { messageBody = it },
            confidentialMode = confidentialMode,
            showConfidentialToggle = showConfidentialToggle,
            onConfidentialToggle = onConfidentialToggle,
            onSend = onSend,
        )
    }
}

@Composable
private fun FilePreSendPreview(fileName: String, fileSize: Long, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(DifftTheme.radius.card))
                .background(DifftTheme.colors.fill),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = fileTypeIcon(fileName)),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                colorFilter = ColorFilter.tint(DifftTheme.colors.icon),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = fileName,
            color = DifftTheme.colors.textPrimary,
            style = DifftTheme.typography.p2,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = FileUtil.readableFileSize(fileSize),
            color = DifftTheme.colors.textSecondary,
            style = DifftTheme.typography.p3
        )
    }
}

@Composable
private fun FilePreSendInputBar(
    messageBody: String,
    onMessageChange: (String) -> Unit,
    confidentialMode: Int,
    showConfidentialToggle: Boolean,
    onConfidentialToggle: () -> Unit,
    onSend: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilePreSendInputField(
                messageBody = messageBody,
                onMessageChange = onMessageChange,
                confidentialMode = confidentialMode,
                showConfidentialToggle = showConfidentialToggle,
                onConfidentialToggle = onConfidentialToggle,
                modifier = Modifier.weight(1f),
            )

            
            Image(
                painter = painterResource(id = R.drawable.chat_ic_send_arrow),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(44.dp)
                    .floatingSurface(DifftTheme.radius.pill, fill = DifftTheme.colors.primary, outlined = false)
                    .clickable { onSend(messageBody) }
                    .padding(10.dp)
            )
        }
    }
}

@Composable
private fun FilePreSendInputField(
    messageBody: String,
    onMessageChange: (String) -> Unit,
    confidentialMode: Int,
    showConfidentialToggle: Boolean,
    onConfidentialToggle: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            
            .confidentialInputBorder(confidentialMode == 1, dimensionResource(R.dimen.chat_input_field_radius))
            .floatingSurface(cornerRadius = dimensionResource(R.dimen.chat_input_field_radius))
            .testTag(FILE_PRESEND_INPUT_TAG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        
        
        DifftClearableTextField(
            value = messageBody,
            onValueChange = { newValue ->
                val utf8Size = newValue.toByteArray(Charsets.UTF_8).size
                val limitedValue = if (utf8Size > MAX_TEXT_FILE_SIZE) {
                    ToastUtil.show(R.string.text_file_exceeds_10mb_limit)
                    newValue.utf8Substring(MAX_TEXT_FILE_SIZE)
                } else {
                    newValue
                }
                onMessageChange(limitedValue)
            },
            onClear = {},
            hint = stringResource(id = R.string.MediaReviewFragment__add_a_message),
            style = DifftTextFieldStyle.Form,
            clearMode = ClearMode.Never,
            maxLines = MESSAGE_INPUT_MAX_LINES,
            containerColor = Color.Transparent,
            
            textStyle = DifftTheme.typography.p2,
            height = null,
            minHeight = 0.dp,
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.weight(1f),
        )

        
        if (showConfidentialToggle) {
            val isEnabled = confidentialMode == 1
            Image(
                painter = painterResource(
                    id = if (isEnabled) R.drawable.chat_btn_confidential_mode_enable
                    else R.drawable.chat_btn_confidential_mode_disable
                ),
                contentDescription = null,
                colorFilter = if (isEnabled) null
                    else ColorFilter.tint(DifftTheme.colors.icon),
                modifier = Modifier
                    .size(44.dp)
                    .clickable { onConfidentialToggle() }
                    .padding(10.dp)
            )
        }
    }
}

internal const val FILE_PRESEND_SCREEN_TAG = "file_presend_screen"
internal const val FILE_PRESEND_INPUT_TAG = "file_presend_input"
