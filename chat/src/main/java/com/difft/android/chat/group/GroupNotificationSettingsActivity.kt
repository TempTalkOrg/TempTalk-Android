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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.network.group.ChangeSelfSettingsInGroupReq
import com.difft.android.network.group.GroupRepo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject

@AndroidEntryPoint
class GroupNotificationSettingsActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var groupRepo: GroupRepo

    private var refreshTrigger = mutableIntStateOf(0)

    companion object {
        fun start(activity: Activity, groupId: String) {
            val intent = Intent(activity, GroupNotificationSettingsActivity::class.java)
            intent.putExtra(KEY_GROUP_ID, groupId)
            activity.startActivity(intent)
        }

        private const val KEY_GROUP_ID = "group_id"
    }

    private lateinit var groupId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        groupId = intent.getStringExtra(KEY_GROUP_ID) ?: ""
        if (groupId.isEmpty()) {
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GroupGlobalNotificationSettingsActivity.REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            // 从全局设置页面返回，触发刷新以更新 globalNotificationType 显示
            refreshTrigger.intValue++
        }
    }

    private fun updateGroupNotificationSettings(isDefaultEnabled: Boolean) {
        val currentUserId = globalServices.myId

        lifecycleScope.launch {
            try {
                val request = ChangeSelfSettingsInGroupReq(
                    notification = null, // 保持当前通知设置不变
                    useGlobal = isDefaultEnabled // 开关开启时传 true，关闭时传 false
                )

                ComposeDialogManager.showWait(this@GroupNotificationSettingsActivity, "")
                val response = withContext(Dispatchers.IO) {
                    groupRepo.changeSelfSettingsInGroup(groupId, currentUserId, request)
                }
                ComposeDialogManager.dismissWait()

                if (response.status == 0) {
                    // 接口调用成功
                    L.i { "[GroupNotificationSettingsActivity] Update group notification settings success: useGlobal=$isDefaultEnabled" }
                } else {
                    // 接口调用失败，显示错误信息
                    ToastUtil.show(response.reason ?: getString(com.difft.android.chat.R.string.operation_failed))
                }
            } catch (e: Exception) {
                L.e { "[GroupNotificationSettingsActivity] Update group notification settings failed: ${e.stackTraceToString()}" }
                ComposeDialogManager.dismissWait()
                ToastUtil.show(getString(com.difft.android.chat.R.string.operation_failed))
            }
        }
    }

    private fun updateGroupNotificationSettingsWithNotification(notification: Int, onSuccess: (() -> Unit)? = null) {
        val currentUserId = globalServices.myId

        lifecycleScope.launch {
            try {
                val request = ChangeSelfSettingsInGroupReq(
                    notification = notification, // 传递选中的通知设置
                    useGlobal = false // 选项切换时，useGlobal 传 false
                )

                ComposeDialogManager.showWait(this@GroupNotificationSettingsActivity, "")
                val response = withContext(Dispatchers.IO) {
                    groupRepo.changeSelfSettingsInGroup(groupId, currentUserId, request)
                }
                ComposeDialogManager.dismissWait()

                if (response.status == 0) {
                    // 接口调用成功，执行回调更新UI状态
                    onSuccess?.invoke()
                    L.i { "[GroupNotificationSettingsActivity] Update group notification settings success: notification=$notification, useGlobal=false" }
                } else {
                    // 接口调用失败，显示错误信息
                    ToastUtil.show(response.reason ?: getString(com.difft.android.chat.R.string.operation_failed))
                }
            } catch (e: Exception) {
                L.e { "[GroupNotificationSettingsActivity] Update group notification settings failed: ${e.stackTraceToString()}" }
                ComposeDialogManager.dismissWait()
                ToastUtil.show(getString(com.difft.android.chat.R.string.operation_failed))
            }
        }
    }

    @Preview
    @Composable
    private fun MainContent() {
        // 全局设置值，用于 DefaultSettingItem 显示，需要能够被刷新
        var globalNotificationType by remember {
            mutableIntStateOf(userManager.getUserData()?.globalNotification ?: 0)
        }

        // 选择项的当前值，从群组成员数据获取
        var currentType by remember {
            mutableIntStateOf(0) // 初始值，稍后从数据库更新
        }

        var isDefaultEnabled by remember {
            mutableStateOf(true) // 默认开启，稍后从数据库更新
        }

        // 异步获取数据库中的 useGlobal 和 notification 值，并响应 refreshTrigger 变化
        LaunchedEffect(refreshTrigger.intValue) {
            // 刷新全局通知设置
            globalNotificationType = userManager.getUserData()?.globalNotification ?: 0
            try {
                val groupMember = wcdb.groupMemberContactor.getFirstObject(
                    (DBGroupMemberContactorModel.gid.eq(groupId))
                        .and(DBGroupMemberContactorModel.id.eq(globalServices.myId))
                )
                isDefaultEnabled = groupMember?.useGlobal ?: false
                currentType = groupMember?.notification ?: 0
            } catch (e: Exception) {
                L.e { "[GroupNotificationSettingsActivity] Failed to get group member data: ${e.stackTraceToString()}" }
                // 如果获取失败，保持默认值
            }
        }


        Column(
            Modifier.fillMaxSize()
        ) {
            TitleBar(
                titleText = getString(com.difft.android.chat.R.string.notification),
                onBackClick = { finish() }
            )

            LazyColumn(modifier = Modifier.Companion.padding(16.dp)) {
                // 默认设置项
                item {
                    DefaultSettingItem(
                        currentType = globalNotificationType,
                        isDefaultEnabled = isDefaultEnabled,
                        onDefaultSettingClick = {
                            GroupGlobalNotificationSettingsActivity.start(this@GroupNotificationSettingsActivity)
                        },
                        onSwitchChanged = { newValue ->
                            isDefaultEnabled = newValue
                            // 调用接口更新群组通知设置
                            updateGroupNotificationSettings(newValue)
                        }
                    )
                }

                // 只有在开关关闭时才显示下面的内容
                if (!isDefaultEnabled) {
                    // 分割线
                    item {
                        Box(
                            modifier = Modifier.Companion
                                .fillMaxWidth()
                                .height(1.dp)
                                .padding(vertical = 16.dp)
                                .background(
                                    Color(
                                        ContextCompat.getColor(
                                            LocalContext.current,
                                            R.color.bg1  // Use bg1 for divider/background
                                        )
                                    )
                                )
                        )
                    }

                    // 自定义设置标题
                    item {
                        Text(
                            text = getString(com.difft.android.chat.R.string.notification_custom_settings),
                            fontSize = 14.sp,
                            color = Color(
                                ContextCompat.getColor(
                                    LocalContext.current,
                                    R.color.t_primary
                                )
                            ),
                            modifier = Modifier.Companion.padding(top = 16.dp, bottom = 5.dp)
                        )
                    }

                    // 通知选项列表
                    items(GlobalNotificationType.entries.size, key = { index -> GlobalNotificationType.entries[index].value }) { index ->
                        val option = GlobalNotificationType.entries[index].value
                        val context = LocalContext.current
                        val backgroundShape = remember(option) {
                            when (option) {
                                GlobalNotificationType.entries.first().value -> RoundedCornerShape(
                                    topStart = 8.dp, topEnd = 8.dp
                                )

                                GlobalNotificationType.entries.last().value -> RoundedCornerShape(
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
                                        // 调用群组通知设置接口
                                        updateGroupNotificationSettingsWithNotification(option) {
                                            // 接口调用成功后，更新本地状态
                                            currentType = option
                                        }
                                    }
                                    .padding(16.dp),
                            ) {
                                val isItemSelected = currentType == option
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
                            if (index < GlobalNotificationType.entries.size - 1) {
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
    }

    @Preview
    @Composable
    private fun DefaultSettingItem(
        currentType: Int = 0,
        isDefaultEnabled: Boolean = true,
        onDefaultSettingClick: (() -> Unit)? = null,
        onSwitchChanged: ((Boolean) -> Unit)? = null
    ) {
        val context = LocalContext.current
        val bgItem = remember {
            Color(
                ContextCompat.getColor(
                    context, R.color.bg_setting_item
                )
            )
        }

        Column(
            modifier = Modifier.Companion
                .fillMaxWidth()
                .background(bgItem, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            // 第一行：左边显示默认设置文字，右边显示开关
            Row(
                verticalAlignment = Alignment.Companion.CenterVertically,
                modifier = Modifier.Companion.fillMaxWidth()
            ) {
                // 左边：Default Setting + 全局设置选项对应的文字
                Row(
                    verticalAlignment = Alignment.Companion.CenterVertically,
                    modifier = Modifier.Companion.weight(1f)
                ) {
                    Text(
                        text = getString(com.difft.android.chat.R.string.notification_default_setting),
                        fontSize = 16.sp,
                        color = Color(
                            ContextCompat.getColor(
                                context, R.color.t_primary
                            )
                        )
                    )
                    Text(
                        text = " (" + when (currentType) {
                            GlobalNotificationType.ALL.value -> getString(com.difft.android.chat.R.string.notification_all)
                            GlobalNotificationType.MENTION.value -> getString(com.difft.android.chat.R.string.notification_mention_only)
                            GlobalNotificationType.OFF.value -> getString(com.difft.android.chat.R.string.notification_off)
                            else -> getString(com.difft.android.chat.R.string.notification_all)
                        } + ")",
                        fontSize = 16.sp,
                        color = if (isDefaultEnabled) {
                            Color(
                                ContextCompat.getColor(
                                    context, R.color.t_info
                                )
                            )
                        } else {
                            Color(
                                ContextCompat.getColor(
                                    context, R.color.t_secondary
                                )
                            )
                        }
                    )
                }

                // 右边：开关
                Switch(
                    checked = isDefaultEnabled,
                    onCheckedChange = { newValue ->
                        onSwitchChanged?.invoke(newValue)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colorResource(id = R.color.t_white),
                        checkedTrackColor = colorResource(id = R.color.primary),
                        uncheckedThumbColor = colorResource(id = R.color.t_white),
                        uncheckedTrackColor = colorResource(id = R.color.gray_600)
                    )
                )
            }

            // 只有在开关开着时才显示第二行和第三行
            if (isDefaultEnabled) {
                // 第二行：提示文字
                Text(
                    text = getString(com.difft.android.chat.R.string.notification_change_default_setting_tip),
                    fontSize = 14.sp,
                    color = Color(
                        ContextCompat.getColor(
                            context, R.color.t_secondary
                        )
                    ),
                    modifier = Modifier.Companion.padding(top = 8.dp)
                )

                // 第三行：蓝色下划线文字
                Text(
                    text = getString(com.difft.android.chat.R.string.notification_update_default_group_setting),
                    fontSize = 14.sp,
                    color = Color(
                        ContextCompat.getColor(
                            context, R.color.t_info
                        )
                    ),
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.Companion
                        .padding(top = 8.dp)
                        .clickable {
                            onDefaultSettingClick?.invoke()
                        }
                )
            }
        }
    }

}
