package com.difft.android.chat.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.difft.android.base.ui.TitleBar
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import difft.android.messageserialization.For
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import javax.inject.Inject

@AndroidEntryPoint
class ChatArchiveSettingsActivity : BaseActivity() {

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    companion object {
        private const val EXTRA_KEY_TARGET_ID = "EXTRA_KEY_TARGET_ID"
        private const val EXTRA_KEY_IS_GROUP = "EXTRA_KEY_IS_GROUP"

        fun start(activity: Activity, target: For) {
            val intent = Intent(activity, ChatArchiveSettingsActivity::class.java)
            intent.putExtra(EXTRA_KEY_TARGET_ID, target.id)
            intent.putExtra(EXTRA_KEY_IS_GROUP, target is For.Group)
            activity.startActivity(intent)
        }
    }

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        val targetId = intent.getStringExtra(EXTRA_KEY_TARGET_ID)
        val isGroup = intent.getBooleanExtra(EXTRA_KEY_IS_GROUP, false)
        if (targetId != null) {
            val target = if (isGroup) For.Group(targetId) else For.Account(targetId)
            defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
                it.create(target)
            }
        } else {
            defaultViewModelCreationExtras
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 验证必需的参数
        val targetId = intent.getStringExtra(EXTRA_KEY_TARGET_ID)
        if (targetId.isNullOrEmpty()) {
            finish()
            return
        }

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                MainContent()
            }
        }
        setContentView(composeView)
    }

    @Composable
    private fun MainContent() {
        val target = chatSettingViewModel.conversation // 直接使用构造方法传递的conversation

        val selectedOption = chatSettingViewModel.messageExpiry.collectAsState()

        val timeList = if (target is For.Group) {
            messageArchiveManager.getGroupDefaultArchiveTimeList()
        } else {
            messageArchiveManager.getDefaultArchiveTimeList()
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding()
        ) {
            TitleBar(
                titleText = getString(R.string.disappearing_messages),
                onBackClick = { finish() }
            )

            ChatArchiveSettingsContent(
                options = timeList,
                selected = selectedOption.value,
                onSelect = { newOption ->
                    // Re-selecting the current value is a no-op.
                    if (selectedOption.value != newOption) {
                        showTimeChangeDialog(selectedOption.value, newOption) {
                            chatSettingViewModel.updateSelectedOption(this@ChatArchiveSettingsActivity, newOption)
                        }
                    }
                },
            )
        }
    }

    /**
     * 显示时间变更确认对话框
     * @param currentTime 当前时间
     * @param newTime 新选择的时间
     * @param onConfirm 确认回调
     */
    private fun showTimeChangeDialog(currentTime: Long, newTime: Long, onConfirm: () -> Unit) {
        val isExtending = newTime > currentTime

        val title = if (isExtending) {
            getString(R.string.chat_archive_extend_time_title)
        } else {
            getString(R.string.chat_archive_shorten_time_title)
        }

        val message = if (isExtending) {
            getString(R.string.chat_archive_extend_time_message)
        } else {
            getString(R.string.chat_archive_shorten_time_message)
        }

        ComposeDialogManager.showMessageDialog(
            context = this,
            title = title,
            message = message,
            confirmText = getString(R.string.chat_dialog_ok),
            cancelText = getString(R.string.chat_dialog_cancel),
            onConfirm = {
                onConfirm()
            }
        )
    }
}
