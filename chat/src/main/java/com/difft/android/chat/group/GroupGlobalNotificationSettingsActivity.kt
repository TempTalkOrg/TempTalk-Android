package com.difft.android.chat.group

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.AppTheme
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.PrivateConfigsRequestBody
import com.difft.android.network.requests.ProfileRequestBody
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class GroupGlobalNotificationSettingsActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    companion object {
        fun start(activity: Activity) {
            val intent = Intent(activity, GroupGlobalNotificationSettingsActivity::class.java)
            activity.startActivityForResult(intent, REQUEST_CODE)
        }

        const val REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val composeView = ComposeView(this)
        composeView.setContent {
            AppTheme(backgroundColorResId = com.difft.android.base.R.color.bg_setting) {
                MainContent()
            }
        }
        setContentView(composeView)
    }

    @Preview
    @Composable
    private fun MainContent() {
        val currentType = remember {
            mutableIntStateOf(userManager.getUserData()?.globalNotification ?: 0)
        }

        Column(
            Modifier.fillMaxSize()
        ) {
            ToolBar()

            ItemViews(currentType) { newType ->
                // 调用接口同步设置到服务端
                lifecycleScope.launch {
                    try {
                        val profileRequestBody = ProfileRequestBody(
                            privateConfigs = PrivateConfigsRequestBody(globalNotification = newType)
                        )

                        ComposeDialogManager.showWait(this@GroupGlobalNotificationSettingsActivity, "")
                        val response = withContext(Dispatchers.IO) {
                            httpClient.httpService.fetchSetProfile(
                                baseAuth = SecureSharedPrefsUtil.getBasicAuth(),
                                profileRequestBody = profileRequestBody
                            ).blockingGet()
                        }

                        ComposeDialogManager.dismissWait()
                        if (response.isSuccess()) {
                            // 接口调用成功，更新本地设置
                            userManager.update {
                                this.globalNotification = newType
                            }
                            // 更新本地状态
                            currentType.intValue = newType

                            setResult(Activity.RESULT_OK)
                        } else {
                            // 接口调用失败，显示错误信息
                            ToastUtil.show(response.reason ?: getString(com.difft.android.chat.R.string.operation_failed))
                        }
                    } catch (e: Exception) {
                        L.e { "[GlobalNotificationSettingsActivity] set global notification failed: ${e.stackTraceToString()}" }
                        ComposeDialogManager.dismissWait()
                        ToastUtil.show(getString(com.difft.android.chat.R.string.operation_failed))
                    }
                }
            }
        }
    }

    @Preview
    @Composable
    private fun ToolBar() {
        val context = LocalContext.current

        val tintBackIc = remember {
            ColorFilter.Companion.tint(
                Color(
                    ContextCompat.getColor(
                        context,
                        R.color.t_primary
                    )
                )
            )
        }
        ConstraintLayout(
            modifier = Modifier
                .height(Dp(52F))
                .fillMaxWidth()
        ) {
            val (icBack, title) = createRefs()
            Image(
                modifier = Modifier.Companion
                    .constrainAs(icBack) {
                        start.linkTo(parent.start, margin = 16.dp)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    }
                    .clickable {
                        onBackPressedDispatcher.onBackPressed()
                    },
                imageVector = ImageVector.Companion.vectorResource(id = com.difft.android.chat.R.drawable.chat_contact_detail_ic_back),
                contentDescription = "ic go back",
                colorFilter = tintBackIc
            )
            Text(
                text = getString(com.difft.android.chat.R.string.notification),
                fontSize = 20.sp,
                fontWeight = FontWeight.Companion.Bold,
                color = Color(
                    ContextCompat.getColor(
                        LocalContext.current, R.color.t_primary
                    )
                ),
                modifier = Modifier.Companion.constrainAs(title) {
                    start.linkTo(icBack.end, margin = 16.dp)
                    top.linkTo(icBack.top)
                    bottom.linkTo(icBack.bottom)
                },
            )
        }
    }

    @Preview
    @Composable
    private fun ItemViews(
        selectedOption: State<Int> = remember { mutableIntStateOf(0) },
        onNewOptionSelected: ((Int) -> Unit)? = null
    ) {
        val options = GlobalNotificationType.entries.map { it.value }

        LazyColumn(modifier = Modifier.Companion.padding(16.dp)) {
            items(options.size, key = { index -> options[index] }) { index ->
                val option = options[index]
                val context = LocalContext.current
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

                val bgItem = remember {
                    Color(
                        ContextCompat.getColor(
                            context, R.color.bg_setting_item
                        )
                    )
                }
                val dividerColor = remember {
                    Color(
                        ContextCompat.getColor(
                            context, R.color.bg_setting
                        )
                    )
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.Companion.CenterVertically,
                        modifier = Modifier.Companion
                            .fillMaxWidth()
                            .background(bgItem, backgroundShape)
                            .clickable {
                                onNewOptionSelected?.invoke(option)
                            }
                            .padding(16.dp),
                    ) {
                        val isItemSelected = selectedOption.value == option
                        val labelTextColor = remember {
                            Color(
                                ContextCompat.getColor(
                                    context, R.color.t_primary
                                )
                            )
                        }

                        // 根据选项显示不同的内容
                        when (option) {
                            GlobalNotificationType.ALL.value -> {
                                Text(
                                    text = getString(com.difft.android.chat.R.string.notification_all),
                                    modifier = Modifier.Companion.weight(1f),
                                    color = labelTextColor,
                                    fontSize = 16.sp
                                )
                            }

                            GlobalNotificationType.MENTION.value -> {
                                Column(
                                    modifier = Modifier.Companion.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = getString(com.difft.android.chat.R.string.notification_mention_only),
                                        color = labelTextColor,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = getString(com.difft.android.chat.R.string.notification_mention_only_description),
                                        fontSize = 14.sp,
                                        color = Color(
                                            ContextCompat.getColor(
                                                context, R.color.t_info
                                            )
                                        )
                                    )
                                }
                            }

                            GlobalNotificationType.OFF.value -> {
                                Text(
                                    text = getString(com.difft.android.chat.R.string.notification_off),
                                    modifier = Modifier.Companion.weight(1f),
                                    color = labelTextColor,
                                    fontSize = 16.sp
                                )
                            }
                        }

                        if (isItemSelected) {
                            val tint: Color = remember {
                                Color(
                                    ContextCompat.getColor(
                                        context, R.color.t_secondary
                                    )
                                )
                            }
                            Image(
                                imageVector = ImageVector.Companion.vectorResource(id = com.difft.android.chat.R.drawable.chat_ic_selected),
                                colorFilter = ColorFilter.Companion.tint(tint),
                                contentDescription = "Checked",
                                modifier = Modifier.Companion.padding(start = 16.dp)
                            )
                        }
                    }

                    // 添加分割线，除了最后一个item
                    if (index < options.size - 1) {
                        Box(
                            modifier = Modifier.Companion
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(dividerColor)
                        )
                    }
                }
            }
        }
    }
}