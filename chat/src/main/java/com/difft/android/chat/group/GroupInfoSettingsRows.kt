package com.difft.android.chat.group

import android.view.View
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.launchOutlivingPage
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.focus.invalid.FocusLeaveNotice
import com.difft.android.chat.search.SearchMessageDestination
import com.difft.android.chat.setting.ChatArchiveSettingsDestination
import com.difft.android.chat.setting.SaveToPhotosSettingsDestination
import com.difft.android.chat.setting.translate.TranslateSettingsDestination
import com.difft.android.network.group.AddOrRemoveMembersReq
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException







internal fun GroupInfoFragment.bindConversationSettingRows() {
    binding.llSearchChatHistory.setOnClickListener {
        detailNavigation().push(this, SearchMessageDestination(groupId, isGroup = true))
    }

    binding.saveToPhotosContainer.setOnClickListener {
        val target = groupId
            .takeIf { it.isNotBlank() }
            ?.let { For.Group(it) } ?: return@setOnClickListener

        detailNavigation().push(this, SaveToPhotosSettingsDestination(target))
    }

    binding.translateContainer.setOnClickListener {
        val target = groupId
            .takeIf { it.isNotBlank() }
            ?.let { For.Group(it) } ?: return@setOnClickListener

        detailNavigation().push(this, TranslateSettingsDestination(target))
    }

    binding.disappearingTimeContainer.setOnClickListener {
        val target = groupId
            .takeIf { it.isNotBlank() }
            ?.let { For.Group(it) } ?: return@setOnClickListener

        detailNavigation().push(this, ChatArchiveSettingsDestination(target))
    }
}


internal fun GroupInfoFragment.bindMembershipRows() {
    if (role == GROUP_ROLE_OWNER) {
        binding.relGroupManagement.visibility = View.VISIBLE
        binding.relGroupManagement.setOnClickListener {
            detailNavigation().push(this, GroupManagementDestination(groupId))
        }

        binding.leaveButton.setText(R.string.group_disband)
        binding.leaveContainer.setOnClickListener { confirmDisband() }
    } else {
        binding.relGroupManagement.visibility = View.GONE
        binding.leaveButton.setText(R.string.group_leave)
        binding.leaveContainer.setOnClickListener { confirmLeave() }
    }
}

private fun GroupInfoFragment.confirmDisband() {
    dismissWithView(ComposeDialogManager.showMessageDialog(
        context = requireActivity(),
        title = getString(R.string.group_disband),
        message = getString(R.string.group_disband_tips),
        confirmText = getString(R.string.group_disband_disband),
        cancelText = getString(R.string.group_leave_cancel),
        onConfirm = {
            
            launchOutlivingPage {
                try {
                    val result = withContext(Dispatchers.IO) {
                        groupRepo.deleteGroup(groupId)
                    }
                    L.i { "[GroupInfo] disband status=${result.status}" }
                    focusRepository.onLocalGroupExit(result)
                    if (result.status == 0) {
                        onPage { it.detailNavigation().closeConversation(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w { "[GroupInfo] deleteGroup error: ${e.stackTraceToString()}" }
                    onPage { ToastUtil.showLong(R.string.chat_net_error) }
                }
            }
        }
    ))
}

private fun GroupInfoFragment.confirmLeave() {
    dismissWithView(ComposeDialogManager.showMessageDialog(
        context = requireActivity(),
        title = getString(R.string.group_leave),
        message = FocusLeaveNotice.message(requireContext(), groupId, getString(R.string.group_leave_notice)),
        confirmText = getString(R.string.group_leave_leave),
        cancelText = getString(R.string.group_leave_cancel),
        onConfirm = {
            
            launchOutlivingPage {
                try {
                    val result = withContext(Dispatchers.IO) {
                        groupRepo.leaveGroup(
                            groupId,
                            AddOrRemoveMembersReq(mutableListOf(globalServices.myId))
                        )
                    }
                    L.i { "[GroupInfo] leave status=${result.status}" }
                    focusRepository.onLocalGroupExit(result)
                    if (result.status == 0) {
                        onPage { it.detailNavigation().closeConversation(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w { "[GroupInfo] leave group error: ${e.javaClass.simpleName}" }
                    onPage { ToastUtil.showLong(R.string.chat_net_error) }
                }
            }
        }
    ))
}
