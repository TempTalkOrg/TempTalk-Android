package com.difft.android.chat.presend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.compose.ConfidentialTipDialogContent
import com.difft.android.chat.setting.sync.ConfidentialModeSyncCoordinator
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FilePreSendActivity : BaseActivity() {

    @Inject
    lateinit var confidentialSync: ConfidentialModeSyncCoordinator

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
            DifftTheme(useFlatBackground = true) {
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
                if (confidentialSync.setConfidentialMode(conversationId, mode)) {
                    confidentialMode.intValue = mode
                } else {
                    com.difft.android.base.widget.ToastUtil.show(getString(R.string.operation_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e(e) { "[FilePreSendActivity] syncConfidentialMode failed" }
                com.difft.android.base.widget.ToastUtil.show(getString(R.string.operation_failed))
            }
        }
    }

    private fun showConfidentialTipDialog(onConfirm: () -> Unit) {
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(
            activity = this,
            showDragHandle = false,
            onDismiss = { }
        ) {
            ConfidentialTipDialogContent(
                title = getString(R.string.chat_confidential_tip_title),
                content = getString(R.string.chat_confidential_tip_content),
                onConfirm = {
                    globalServices.userManager.update { hasShownConfidentialTip = true }
                    dialog?.dismiss()
                    onConfirm()
                }
            )
        }
    }
}
