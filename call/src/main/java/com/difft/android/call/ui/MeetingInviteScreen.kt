package com.difft.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.handler.InviteCallHandler
import com.difft.android.call.LCallViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 会议邀请页面的状态枚举
 */
enum class InviteScreenState {
    INITIAL,      // 初始页面 - 显示成员列表和添加按钮
    ADD_MEMBERS,  // 添加成员页面 - 显示联系人列表
    CONFIRM       // 确认页面 - 显示已选成员和邀请按钮
}

/**
 * 会议邀请页面的状态枚举
 */
enum class InviteViewState {
    DISMISS,      // 初始页面 - 显示成员列表和添加按钮
    INVITE,  // 添加成员页面 - 显示联系人列表
}


/**
 * 会议邀请主页面
 * 使用 Fragment 实现底部弹窗，参考 ChatSelectBottomSheetFragment 的实现方式
 *
 * @param callViewModel 通话的ViewModel
 * @param inviteCallHandler 邀请处理器，包含邀请相关的状态和逻辑
 * @param onInviteViewAction 回调函数，用于处理邀请视图的操作
 */
@OptIn(FlowPreview::class)
@Composable
fun MeetingInviteScreen(
    callViewModel: LCallViewModel,
    inviteCallHandler: InviteCallHandler?,
    onInviteViewAction: (InviteViewState) -> Unit
) {
    if (inviteCallHandler == null) { return }

    val context = LocalContext.current
    val showInviteViewEnable by callViewModel.callUiController.showInviteViewEnable.collectAsState(false)
    val latestInviteHandler = rememberUpdatedState(inviteCallHandler)
    val latestInviteAction = rememberUpdatedState(onInviteViewAction)

    // 使用 LaunchedEffect 监听状态变化，显示/隐藏 Fragment
    LaunchedEffect(context) {
        val activity = context as? androidx.fragment.app.FragmentActivity ?: return@LaunchedEffect
        snapshotFlow { showInviteViewEnable }
            .distinctUntilChanged()
            .debounce(100)
            .collectLatest { shouldShow ->
                if (shouldShow) {
                    // 检查是否已经显示了 Fragment
                    val existingFragment = activity.supportFragmentManager
                        .findFragmentByTag("MeetingInviteBottomSheet") as? MeetingInviteBottomSheetFragment

                    if (existingFragment == null) {
                        val fragment = MeetingInviteBottomSheetFragment.newInstance()
                        latestInviteHandler.value?.let { handler ->
                            fragment.setInviteCallHandler(handler)
                        }
                        fragment.onInviteAction = { action ->
                            callViewModel.callUiController.setShowInviteViewEnable(false)
                            latestInviteAction.value.invoke(action)
                        }
                        try {
                            fragment.show(activity.supportFragmentManager, "MeetingInviteBottomSheet")
                        } catch (e: IllegalStateException) {
                            L.e { "[call] MeetingInvite show fragment error: ${e.message}" }
                        }
                    }
                } else {
                    // 隐藏 Fragment
                    val existingFragment = activity.supportFragmentManager
                        .findFragmentByTag("MeetingInviteBottomSheet") as? MeetingInviteBottomSheetFragment
                    existingFragment?.dismiss()
                }
            }
    }
}