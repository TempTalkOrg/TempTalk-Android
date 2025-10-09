package com.difft.android.chat.ui

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.recent.RecentChatUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.message.shouldShowFail
import com.difft.android.chat.widget.AttachMessageView
import difft.android.messageserialization.model.AttachmentStatus


open class AttachContentAdapter : MessageContentAdapter() {
    companion object {
        const val TAG = "AttachContentAdapter"
    }

    class AttachContentViewHolder(
        val attachContentView: AttachMessageView,
        val textView: TextView,
        val coverView: View,
        val failView: ConstraintLayout
    ) : ContentViewHolder()

    override val layoutRes: Int = R.layout.chat_item_content_attach

    override fun onBindContentView(message: ChatMessage, viewHolder: ContentViewHolder) {
        message as TextChatMessage
        viewHolder as AttachContentViewHolder

        viewHolder.attachContentView.setupAttachmentView(message)

        if (!TextUtils.isEmpty(message.message)) {
            viewHolder.textView.visibility = View.VISIBLE

            viewHolder.textView.autoLinkMask = 0
            viewHolder.textView.movementMethod = null

            LinkTextUtils.setMarkdownToTextview(viewHolder.textView.context, message.message.toString(), viewHolder.textView, message.mentions)
        } else {
            viewHolder.textView.visibility = View.GONE
        }

        if (message.isConfidential()) {
            viewHolder.coverView.visibility = View.VISIBLE
            viewHolder.coverView.setOnClickListener {
                viewHolder.attachContentView.openFile()
                RecentChatUtil.emitConfidentialRecipient(message)
            }
        } else {
            viewHolder.coverView.visibility = View.GONE
        }

        viewHolder.failView.visibility = if (message.shouldShowFail()) View.VISIBLE else View.GONE
    }


    override fun createViewHolder(viewGroup: ViewGroup): ContentViewHolder {
        return AttachContentViewHolder(
            viewGroup.findViewById(R.id.attach_content_view),
            viewGroup.findViewById(R.id.textView),
            viewGroup.findViewById(R.id.v_cover),
            viewGroup.findViewById(R.id.cl_fail)
        )
    }
}