package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.difft.android.base.BaseActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.compose.input.DifftTextFieldStyle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.WaitDialogEffect
import com.difft.android.chat.R
import com.difft.android.setting.viewmodel.SetCustomIdViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlin.getValue

/** Server-side contact-ID rules: 6-20 characters. */
private const val MIN_CUSTOM_ID_LENGTH = 6
private const val MAX_CUSTOM_ID_LENGTH = 20

@AndroidEntryPoint
class SetCustomIdActivity : BaseActivity() {

    private val viewModel: SetCustomIdViewModel by viewModels()

    companion object {

        const val EXTRA_CONTACT_ID = "EXTRA_CONTACT_ID"

        fun startActivity(activity: Context, contactId: String) {
            val intent = Intent(activity, SetCustomIdActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_ID, contactId)
            }
            activity.startActivity(intent)
        }
    }

    private val contactId: String by lazy {
        intent.getStringExtra(EXTRA_CONTACT_ID) ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeView()
    }

    private fun initializeView() {
        setContent {
            DifftTheme {
                SetOwnIdScreen(
                    viewModel = viewModel,
                    initialValue = contactId,
                    onBack = { finish() },
                    onSave = { customUid -> viewModel.submitCustomUid(customUid, callback = { status ->
                        handleSaveButtonClick(status)
                    }) }
                )
            }
        }
    }

    private fun handleSaveButtonClick(status: Int) {
        if (status == 0) {
            finish()
        }
    }
}

@Composable
fun SetOwnIdScreen(
    viewModel: SetCustomIdViewModel,
    initialValue: String = "",
    onBack: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by remember { mutableStateOf(initialValue) }
    val errorTip by viewModel.errorTip.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val isValid = input.length in MIN_CUSTOM_ID_LENGTH..MAX_CUSTOM_ID_LENGTH

    // The view model invokes its callback (which finishes this Activity on success) before it
    // clears isLoading, so the flag never reaches false in a live composition on the happy path —
    // the effect's dispose is what releases the dialog there.
    WaitDialogEffect(isLoading)

    // 验证函数：检查输入是否符合规则
    fun validateInput(text: String): Boolean {
        if (text.isEmpty()) {
            viewModel.resetErrorTip()
            return false
        }
        // 规则1: 需以字母（a-z, A-Z）或下划线 (_) 开头
        val firstChar = text[0]
        val isEnglishLetter = firstChar in 'a'..'z' || firstChar in 'A'..'Z'
        if (!isEnglishLetter && firstChar != '_') {
            viewModel.setErrorTip(ResUtils.getString(com.difft.android.R.string.settings_contact_ID_status_uid_invalid_1))
            return false
        }
        // 规则2: 仅支持字母（a-z, A-Z）、数字（0-9）、下划线 (_) 或连字符 (-)
        val rule2CheckResult = text.all { char ->
            (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9') || char == '_' || char == '-'
        }
        if(!rule2CheckResult) {
            viewModel.setErrorTip(ResUtils.getString(com.difft.android.R.string.settings_contact_ID_status_uid_invalid_2))
            return false
        }
        
        // 验证通过，清除错误提示
        viewModel.resetErrorTip()
        return true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = DifftTheme.colors.background),
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(modifier = Modifier.height(topInset))
        TitleBar(
            titleText = ResUtils.getString(com.difft.android.R.string.settings_contact_ID_title),
            onBackClick = onBack,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // The library form field owns the box, the error line and the character counter; the
        // counter takes the trailing slot, so there is no clear icon here (library "word count").
        DifftClearableTextField(
            value = input,
            onValueChange = { newValue ->
                input = newValue

                // 输入时重置错误状态显示
                viewModel.resetResponseStatus()
                viewModel.resetErrorTip()

                // 实时验证输入内容
                if (newValue.length >= MIN_CUSTOM_ID_LENGTH) {
                    validateInput(newValue)
                } else {
                    viewModel.setErrorTip(ResUtils.getString(com.difft.android.R.string.settings_contact_ID_status_uid_length_invalid))
                }
            },
            onClear = {},
            hint = ResUtils.getString(com.difft.android.R.string.settings_contact_ID_input_hint),
            style = DifftTextFieldStyle.Form,
            isError = errorTip != null,
            supportingText = errorTip,
            showCounter = true,
            maxLength = MAX_CUSTOM_ID_LENGTH,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            modifier = Modifier.padding(horizontal = 16.dp),
            text = ResUtils.getString(com.difft.android.R.string.settings_contact_ID_valid_tip),
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Save Button
        DifftButton(
            text = ResUtils.getString(com.difft.android.R.string.settings_contact_ID_button_save),
            onClick = {
                // 防止加载时重复点击
                if (isLoading) return@DifftButton

                // 再次验证输入内容是否合法
                if (validateInput(input)) {
                    onSave(input)
                }
            },
            enabled = isValid,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
    }
}