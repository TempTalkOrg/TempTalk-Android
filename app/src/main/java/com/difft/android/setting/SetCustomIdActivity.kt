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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.LottieAnimationView
import com.difft.android.base.utils.ResUtils
import com.difft.android.chat.R
import com.difft.android.setting.viewmodel.SetCustomIdViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlin.getValue

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

    val isValid = input.length in 6..20

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
            .background(color = colorResource(com.difft.android.base.R.color.bg1)),
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(modifier = Modifier.height(topInset))
        // Top AppBar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.chat_contact_detail_ic_back), // 替换为你的返回图标
                contentDescription = "Back",
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() },
                tint = colorResource(com.difft.android.base.R.color.t_secondary)
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ResUtils.getString(com.difft.android.R.string.settings_contact_ID_title),
                    style = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(510),
                        color = colorResource(com.difft.android.base.R.color.t_primary),
                    )
                )
            }

            // 占位，保证左右对称
            Spacer(modifier = Modifier.size(24.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // TextField
        OutlinedTextField(
            value = input,
            onValueChange = { newValue ->
                if (newValue.length <= 20) {
                    input = newValue
                    
                    // 输入时重置错误状态显示
                    viewModel.resetResponseStatus()
                    viewModel.resetErrorTip()
                    
                    // 实时验证输入内容
                    if (newValue.isNotEmpty() && newValue.length >= 6) {
                        validateInput(newValue)
                    } else {
                        viewModel.setErrorTip(ResUtils.getString(com.difft.android.R.string.settings_contact_ID_status_uid_length_invalid))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            placeholder = {
                Text(text = ResUtils.getString(com.difft.android.R.string.settings_contact_ID_input_hint))
            },
            textStyle = TextStyle(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight(400),
                color = colorResource(com.difft.android.base.R.color.t_primary),
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colorResource(com.difft.android.base.R.color.line),
                unfocusedBorderColor = colorResource(com.difft.android.base.R.color.line),
            ),
            trailingIcon = {
                if (input.isNotEmpty()) {
                    IconButton(onClick = { input = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear text"
                        )
                    }
                }
            }
        )

        if (errorTip != null) {
            errorTip?.let { text ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = text,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        color = colorResource(com.difft.android.base.R.color.t_error),
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            modifier = Modifier.padding(horizontal = 16.dp),
            text = ResUtils.getString(com.difft.android.R.string.settings_contact_ID_valid_tip),

            // SF/P3
            style = TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight(400),
                color = colorResource(com.difft.android.base.R.color.t_secondary),
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Save Button
        Button(
            onClick = {
                // 防止加载时重复点击
                if (isLoading) return@Button

                // 再次验证输入内容是否合法
                if(validateInput(input)) {
                    onSave(input)
                }
            },
            enabled = isValid,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(com.difft.android.base.R.color.primary),
                disabledContainerColor = colorResource(com.difft.android.base.R.color.t_disable),
                contentColor = colorResource(com.difft.android.base.R.color.t_white),
                disabledContentColor = colorResource(com.difft.android.base.R.color.t_white)
            )
        ) {
            if (isLoading) {
                LottieLoadingAnimation()
            } else {
                Text(
                    text = ResUtils.getString(com.difft.android.R.string.settings_contact_ID_button_save),
                    // SF/P2
                    style = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        color = colorResource(com.difft.android.base.R.color.t_white),
                        textAlign = TextAlign.Center,
                    )
                )
            }
        }
    }
}

@Composable
fun LottieLoadingAnimation() {
    AndroidView(
        factory = { ctx ->
            LottieAnimationView(ctx).apply {
                setAnimation("login_loading_anim.json")
                playAnimation()
            }
        },
        update = { view ->
            if (!view.isAnimating) {
                view.playAnimation()
            }
        },
        modifier = Modifier.size(24.dp)
    )
}