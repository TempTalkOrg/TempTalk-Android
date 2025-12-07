package com.difft.android.chat.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rxjava3.subscribeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.difft.android.base.ui.TitleBar
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.archive.MessageArchiveUtil
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
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
            DifftTheme(useSecondaryBackground = true) {
                MainContent()
            }
        }
        setContentView(composeView)
    }

    @Preview
    @Composable
    private fun MainContent() {
        val target = chatSettingViewModel.conversation // 直接使用构造方法传递的conversation

        val selectedOption = MessageArchiveUtil
            .archiveTimeUpdate
            .filter { it.first == target.id }
            .map { it.second }
            .subscribeAsState(initial = messageArchiveManager.getDefaultMessageArchiveTime())

        Column(
            Modifier.fillMaxSize()
        ) {
            TitleBar(
                titleText = getString(R.string.disappearing_messages),
                onBackClick = { finish() }
            )

            ItemViews(target, selectedOption) { newOption ->
                // 如果选择的时间没有发生变化，不触发任何操作
                if (selectedOption.value != newOption) {
                    showTimeChangeDialog(selectedOption.value, newOption) {
                        chatSettingViewModel.updateSelectedOption(this@ChatArchiveSettingsActivity, newOption)
                    }
                }
            }

            ExplainView()
        }
    }

    @Preview
    @Composable
    private fun ExplainView() {
        Text(
            text = getString(R.string.disappearing_messages_tips),
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 16.dp),
            fontSize = 12.sp,
            color = Color(
                ContextCompat.getColor(
                    LocalContext.current, com.difft.android.base.R.color.t_secondary
                )
            )
        )
    }

    @Preview
    @Composable
    private fun ItemViews(
        target: For? = null,
        selectedOption: State<Long> = mutableLongStateOf(messageArchiveManager.getDefaultMessageArchiveTime()),
        onNewOptionSelected: ((Long) -> Unit)? = null
    ) {
        val timeList = if (target is For.Group) messageArchiveManager.getGroupDefaultArchiveTimeList() else messageArchiveManager.getDefaultArchiveTimeList()
        LazyColumn(modifier = Modifier.padding(16.dp)) {
            val (options, count) = if (selectedOption.value in timeList) {
                timeList to timeList.size
            } else {
                (timeList + selectedOption.value) to (timeList.size + 1)
            }

            items(count, key = { index -> options[index] }) { index ->
                val option = options[index]
                val backgroundShape = remember(option) {
                    when (option) {
                        options.firstOrNull() -> RoundedCornerShape(
                            topStart = 8.dp, topEnd = 8.dp
                        )

                        options.last() -> RoundedCornerShape(
                            bottomStart = 8.dp, bottomEnd = 8.dp
                        )

                        else -> RectangleShape
                    }
                }

                val context = LocalContext.current
                val bgItem = remember {
                    Color(
                        ContextCompat.getColor(
                            context, com.difft.android.base.R.color.bg_setting_item
                        )
                    )
                }
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(bgItem, backgroundShape)
                            .clickable {
                                onNewOptionSelected?.invoke(option)
                            }
                            .padding(start = 16.dp, end = 16.dp),
                    ) {
                        val isItemSelected = selectedOption.value == option
                        val labelTextColor = remember {
                            Color(
                                ContextCompat.getColor(
                                    context, com.difft.android.base.R.color.t_primary
                                )
                            )
                        }

                        Text(
                            text = option.toArchiveTimeDisplayText(),
                            modifier = Modifier.weight(1f),
                            color = labelTextColor
                        )

                        if (isItemSelected) {
                            val tint: Color = remember {
                                Color(
                                    ContextCompat.getColor(
                                        context, com.difft.android.base.R.color.t_secondary
                                    )
                                )
                            }
                            Image(
                                imageVector = ImageVector.vectorResource(id = R.drawable.chat_ic_selected),
                                colorFilter = ColorFilter.tint(tint),
                                contentDescription = "Checked",
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }

                    // 添加分割线，除了最后一个item
                    if (index < count - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(
                                    Color(
                                        ContextCompat.getColor(
                                            context,
                                            com.difft.android.base.R.color.bg_setting
                                        )
                                    )
                                )
                        )
                    }
                }
            }
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