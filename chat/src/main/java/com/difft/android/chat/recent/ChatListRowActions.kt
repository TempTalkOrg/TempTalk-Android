package com.difft.android.chat.recent

import android.view.View
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ChativePopupView
import com.difft.android.base.widget.ChativePopupWindow
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.focus.FocusActionLauncher






class ChatListRowActions(
    private val activity: FragmentActivity,
    private val viewModel: RecentChatViewModel,
    private val focusActions: FocusActionLauncher,
    private val myId: String,
) {
    private var popupWindow: PopupWindow? = null

    




    fun showItemActions(rootView: View, data: RoomViewData, touchX: Int, touchY: Int) {
        val itemList = mutableListOf<ChativePopupView.Item>().apply {
            if (data.displayUnreadNum > 0) {
                add(item(R.drawable.chat_icon_mark_read, R.string.chat_action_mark_read) { viewModel.markAsRead(data) })
            } else {
                add(item(R.drawable.chat_icon_mark_unread, R.string.chat_action_mark_unread) { viewModel.markAsUnread(data) })
            }

            if (data.isPinned) {
                add(item(R.drawable.chat_icon_unstick, R.string.chat_unstick) { togglePin(data) })
            } else {
                add(item(R.drawable.chat_icon_stick, R.string.chat_stick) { togglePin(data) })
            }

            when (data.focusAction) {
                FocusRowAction.START -> add(item(R.drawable.chat_ic_focus, R.string.focus_this_group) { toggleFocus(data) })
                FocusRowAction.END -> add(item(R.drawable.chat_ic_focus, R.string.focus_dialog_end_focus) { toggleFocus(data) })
                FocusRowAction.NONE -> Unit
            }

            if (data.roomId != myId) {
                val destructive = ContextCompat.getColor(activity, com.difft.android.base.R.color.error)
                add(
                    ChativePopupView.Item(
                        drawable = ResUtils.getDrawable(R.drawable.chat_message_action_delete),
                        label = activity.getString(R.string.me_delete),
                        iconColor = destructive,
                        textColor = destructive,
                    ) {
                        confirmDelete(data)
                        popupWindow?.dismiss()
                    }
                )
            }
        }
        popupWindow = ChativePopupWindow.showAtTouchPosition(rootView, itemList, touchX, touchY)
    }

    
    fun showFoldedGroupsActions(rootView: View, touchX: Int, touchY: Int) {
        val items = listOf(item(R.drawable.chat_ic_focus, R.string.focus_dialog_end_focus) { endFocus() })
        popupWindow = ChativePopupWindow.showAtTouchPosition(rootView, items, touchX, touchY)
    }

    fun dismissPopup() {
        popupWindow?.dismiss()
        popupWindow = null
    }

    fun toggleRead(data: RoomViewData) {
        if (data.displayUnreadNum > 0) viewModel.markAsRead(data) else viewModel.markAsUnread(data)
    }

    
    fun togglePin(data: RoomViewData) {
        viewModel.pinChattingRoom(!data.isPinned, data)
    }

    
    fun toggleFocus(data: RoomViewData) {
        when (data.focusAction) {
            FocusRowAction.START -> focusActions.requestFocus(activity, data.roomId, data.roomName?.toString().orEmpty())
            FocusRowAction.END -> endFocus()
            FocusRowAction.NONE -> Unit
        }
    }

    fun endFocus() {
        focusActions.endFocus(activity)
    }

    fun confirmDelete(data: RoomViewData) {
        val name = data.roomName?.toString()?.takeIf { it.isNotBlank() } ?: data.roomId
        ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(R.string.chat_delete_chat_title),
            message = activity.getString(R.string.chat_delete_chat_message, name),
            confirmText = activity.getString(R.string.me_delete),
            cancelText = activity.getString(R.string.group_leave_cancel),
            confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(activity, com.difft.android.base.R.color.error)),
            onConfirm = {
                viewModel.deleteChat(data)
                ToastUtil.show(R.string.chat_toast_deleted)
            }
        )
    }

    private fun item(icon: Int, label: Int, action: () -> Unit) =
        ChativePopupView.Item(ResUtils.getDrawable(icon), activity.getString(label)) {
            action()
            popupWindow?.dismiss()
        }
}
