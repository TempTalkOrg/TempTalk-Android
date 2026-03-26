package com.difft.android.chat.presend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.compose.ConfidentialTipDialogContent
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ConversationSetRequestBody
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FilePreSendActivity : BaseActivity() {

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    override fun shouldApplySystemBarsPadding(): Boolean = false

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_FILE_SIZE = "file_size"
        const val EXTRA_CONFIDENTIAL_MODE = "confidential_mode"
        const val EXTRA_SHOW_CONFIDENTIAL_TOGGLE = "show_confidential_toggle"
        const val EXTRA_CONVERSATION_ID = "conversation_id"

        const val RESULT_FILE_PATH = "result_file_path"
        const val RESULT_FILE_NAME = "result_file_name"
        const val RESULT_MIME_TYPE = "result_mime_type"
        const val RESULT_BODY = "result_body"
        const val RESULT_CONFIDENTIAL_MODE = "result_confidential_mode"

        fun createIntent(
            context: Context,
            filePath: String,
            fileName: String,
            mimeType: String,
            fileSize: Long,
            confidentialMode: Int,
            showConfidentialToggle: Boolean,
            conversationId: String
        ): Intent {
            return Intent(context, FilePreSendActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_FILE_SIZE, fileSize)
                putExtra(EXTRA_CONFIDENTIAL_MODE, confidentialMode)
                putExtra(EXTRA_SHOW_CONFIDENTIAL_TOGGLE, showConfidentialToggle)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
        }
    }

    // State held at Activity level so dialog callbacks can update it
    private var confidentialMode = mutableIntStateOf(0)
    private var conversationId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: ""
        val fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
        confidentialMode.intValue = intent.getIntExtra(EXTRA_CONFIDENTIAL_MODE, 0)
        val showToggle = intent.getBooleanExtra(EXTRA_SHOW_CONFIDENTIAL_TOGGLE, false)
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: ""

        setContent {
            DifftTheme {
                FilePreSendScreen(
                    fileName = fileName,
                    fileSize = fileSize,
                    confidentialMode = confidentialMode.intValue,
                    showConfidentialToggle = showToggle,
                    onBack = { finish() },
                    onConfidentialToggle = { toggleConfidentialMode() },
                    onSend = { messageBody ->
                        val resultIntent = Intent().apply {
                            putExtra(RESULT_FILE_PATH, filePath)
                            putExtra(RESULT_FILE_NAME, fileName)
                            putExtra(RESULT_MIME_TYPE, mimeType)
                            putExtra(RESULT_BODY, messageBody)
                            putExtra(RESULT_CONFIDENTIAL_MODE, confidentialMode.intValue)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                )
            }
        }
    }

    private fun toggleConfidentialMode() {
        val newMode = if (confidentialMode.intValue == 1) 0 else 1
        if (newMode == 1 && globalServices.userManager.getUserData()?.hasShownConfidentialTip != true) {
            showConfidentialTipDialog {
                syncConfidentialModeToServer(newMode)
            }
        } else {
            syncConfidentialModeToServer(newMode)
        }
    }

    private fun syncConfidentialModeToServer(mode: Int) {
        if (conversationId.isEmpty()) return
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.httpService.fetchConversationSet(
                        SecureSharedPrefsUtil.getBasicAuth(),
                        ConversationSetRequestBody(conversationId, confidentialMode = mode)
                    )
                }
                if (result.status == 0) {
                    confidentialMode.intValue = mode
                } else {
                    com.difft.android.base.widget.ToastUtil.show(getString(R.string.operation_failed))
                }
            } catch (e: Exception) {
                L.e(e) { "[FilePreSendActivity] syncConfidentialMode failed" }
                com.difft.android.base.widget.ToastUtil.show(getString(R.string.operation_failed))
            }
        }
    }

    private fun showConfidentialTipDialog(onConfirm: () -> Unit) {
        globalServices.userManager.update { hasShownConfidentialTip = true }
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(
            activity = this,
            onDismiss = { }
        ) {
            ConfidentialTipDialogContent(
                title = getString(R.string.chat_confidential_tip_title),
                content = getString(R.string.chat_confidential_tip_content),
                onConfirm = {
                    dialog?.dismiss()
                    onConfirm()
                }
            )
        }
    }
}

@Composable
private fun FilePreSendScreen(
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
            .background(DifftTheme.colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Back button
        Image(
            painter = painterResource(id = com.difft.android.base.R.drawable.chative_ic_back),
            contentDescription = null,
            colorFilter = ColorFilter.tint(DifftTheme.colors.onSurface),
            modifier = Modifier
                .size(44.dp)
                .clickable { onBack() }
                .padding(10.dp)
        )

        // File info - centered
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.mipmap.chat_attachment_file_logo),
                contentDescription = null
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = fileName,
                color = DifftTheme.colors.onSurface,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = FileUtil.readableFileSize(fileSize),
                color = DifftTheme.colors.onSurfaceVariant,
                fontSize = 14.sp
            )
        }

        // Bottom input area with confidential style
        val isConfidential = confidentialMode == 1

        // Dashed line above input area
        if (isConfidential) {
            val primaryColor = DifftTheme.colors.primary
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
            ) {
                drawLine(
                    color = primaryColor,
                    start = Offset.Zero,
                    end = Offset(size.width, 0f),
                    strokeWidth = 0.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 3.dp.toPx())
                    )
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isConfidential) colorResource(id = com.difft.android.base.R.color.bg_confidential_area)
                    else Color.Transparent
                )
                .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Input field with confidential toggle inside
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(
                        width = 0.5.dp,
                        color = colorResource(id = com.difft.android.base.R.color.line),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .background(
                        color = colorResource(
                            id = if (isConfidential) com.difft.android.base.R.color.bg1
                            else com.difft.android.base.R.color.bg2
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Text input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (messageBody.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.MediaReviewFragment__add_a_message),
                            color = DifftTheme.colors.textDisabled,
                            fontSize = 16.sp
                        )
                    }
                    BasicTextField(
                        value = messageBody,
                        onValueChange = { messageBody = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = DifftTheme.colors.textPrimary,
                            fontSize = 16.sp
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(DifftTheme.colors.primary)
                    )
                }

                // Confidential toggle inside input field
                if (showConfidentialToggle) {
                    val isEnabled = confidentialMode == 1
                    Image(
                        painter = painterResource(
                            id = if (isEnabled) R.drawable.chat_btn_confidential_mode_enable
                            else R.drawable.chat_btn_confidential_mode_disable
                        ),
                        contentDescription = null,
                        colorFilter = if (isEnabled) null
                            else ColorFilter.tint(colorResource(id = com.difft.android.base.R.color.icon)),
                        modifier = Modifier
                            .size(44.dp)
                            .clickable { onConfidentialToggle() }
                            .padding(10.dp)
                    )
                }
            }

            // Send button
            Image(
                painter = painterResource(id = R.drawable.chat_icon_send),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(32.dp)
                    .clickable { onSend(messageBody) }
            )
        }
    }
}
