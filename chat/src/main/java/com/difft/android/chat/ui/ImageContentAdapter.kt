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
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.message.shouldShowFail
import com.difft.android.chat.widget.ImageAndVideoMessageView
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies

open class ImageContentAdapter : MessageContentAdapter() {
    class ImageContentViewHolder(
        val imageMessageView: ImageAndVideoMessageView,
        val textView: TextView,
        val coverView: TextView,
        val failView: ConstraintLayout
    ) : ContentViewHolder()

    val application = ApplicationDependencies.getApplication()
    override val layoutRes: Int = R.layout.chat_item_content_image

    override fun onBindContentView(message: ChatMessage, viewHolder: ContentViewHolder) {
        message as TextChatMessage
        viewHolder as ImageContentViewHolder

        viewHolder.imageMessageView.setupImageView(message)

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
        } else {
            viewHolder.coverView.visibility = View.GONE
        }

        viewHolder.failView.visibility = if (message.shouldShowFail()) View.VISIBLE else View.GONE
    }

    override fun createViewHolder(viewGroup: ViewGroup): ContentViewHolder {
        return ImageContentViewHolder(
            viewGroup.findViewById(R.id.imageMessageView),
            viewGroup.findViewById(R.id.textView),
            viewGroup.findViewById(R.id.v_cover),
            viewGroup.findViewById(R.id.cl_fail)
        )
    }
}