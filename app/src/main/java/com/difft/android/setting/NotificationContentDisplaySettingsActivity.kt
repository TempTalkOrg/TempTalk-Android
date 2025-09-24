package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.difft.android.base.BaseActivity
import com.difft.android.base.R
import com.difft.android.base.user.UserManager
import com.difft.android.base.user.NotificationContentDisplayType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class NotificationContentDisplaySettingsActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    companion object {
        fun start(activity: Activity) {
            val intent = Intent(activity, NotificationContentDisplaySettingsActivity::class.java)
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val composeView = ComposeView(this)
        composeView.setContent {
            MainContent()
        }
        setContentView(composeView)
    }

    @Preview
    @Composable
    private fun MainContent() {
        val currentType = remember {
            mutableIntStateOf(userManager.getUserData()?.notificationContentDisplayType ?: 0)
        }

        Column(
            Modifier.Companion
                .fillMaxSize()
                .background(
                    Color(
                        ContextCompat.getColor(
                            LocalContext.current,
                            R.color.bg_setting
                        )
                    )
                )
        ) {
            ToolBar()

            ItemViews(currentType) { newType ->
                // 更新用户设置
                userManager.update {
                    this.notificationContentDisplayType = newType
                }
                // 更新本地状态
                currentType.intValue = newType
                // 返回上一页
                finish()
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
            modifier = Modifier.Companion
                .height(Dp(52F))
                .fillMaxSize()
                .background(
                    Color(
                        ContextCompat.getColor(
                            context,
                            R.color.bg1
                        )
                    )
                )
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
                text = getString(com.difft.android.chat.R.string.notification_content),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
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
        val options = NotificationContentDisplayType.entries.map { it.value }

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

                        Text(
                            text = when (option) {
                                NotificationContentDisplayType.NAME_AND_CONTENT.value -> getString(com.difft.android.chat.R.string.notification_display_name_and_content)
                                NotificationContentDisplayType.NAME_ONLY.value -> getString(com.difft.android.chat.R.string.notification_only_name)
                                NotificationContentDisplayType.NO_NAME_OR_CONTENT.value -> getString(com.difft.android.chat.R.string.notification_no_name_or_content)
                                else -> getString(com.difft.android.chat.R.string.notification_display_name_and_content)
                            },
                            modifier = Modifier.Companion.weight(1f),
                            color = labelTextColor,
                            fontSize = 16.sp
                        )

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
                        androidx.compose.foundation.layout.Box(
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