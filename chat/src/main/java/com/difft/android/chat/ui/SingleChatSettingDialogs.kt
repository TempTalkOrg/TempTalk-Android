package com.difft.android.chat.ui

import android.content.Context
import android.content.Intent
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R


class SingleChatSettingDestination(private val contactId: String) : DetailDestination {
    override fun newFragment(): Fragment = SingleChatSettingFragment.newInstance(contactId)
    override fun shellIntent(context: Context): Intent = SingleChatSettingActivity.intent(context, contactId)
    override val subjectId: String get() = contactId
}


internal object SingleChatSettingDialogs {

    fun confirmRemove(page: Fragment, onConfirm: () -> Unit) {
        val activity = page.requireActivity()
        page.dismissWithView(ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(R.string.contact_remove_dialog_title),
            message = activity.getString(R.string.contact_remove_dialog_tips),
            confirmText = activity.getString(R.string.contact_remove_dialog_yes),
            cancelText = activity.getString(R.string.contact_remove_dialog_cancel),
            onConfirm = onConfirm,
        ))
    }

    fun confirmBlock(page: Fragment, onBlock: () -> Unit) {
        val activity = page.requireActivity()
        var dialog: ComposeDialog? = null
        dialog = page.dismissWithView(ComposeDialogManager.showBottomDialog(
            activity = activity,
            layoutId = R.layout.layout_block_dialog,
            onDismiss = {  },
            onViewCreated = { v ->
                v.findViewById<AppCompatTextView>(R.id.tv_block_tips).text =
                    activity.getString(R.string.contact_block_users_tips, PackageUtil.getAppName())
                v.findViewById<AppCompatTextView>(R.id.tv_block).setOnClickListener {
                    dialog?.dismiss()
                    onBlock()
                }
                v.findViewById<AppCompatTextView>(R.id.tv_cancel).setOnClickListener { dialog?.dismiss() }
            }
        ))
    }
}
