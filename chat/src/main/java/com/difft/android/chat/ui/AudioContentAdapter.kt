package com.difft.android.chat.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.message.shouldShowFail
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.chat.widget.VoiceMessageView
import difft.android.messageserialization.model.AttachmentStatus

open class AudioContentAdapter : MessageContentAdapter() {
    override val layoutRes: Int = R.layout.chat_item_content_audio

    override fun onBindContentView(message: ChatMessage, contentViewHolder: ContentViewHolder) {
        message as TextChatMessage
        val viewHolder = contentViewHolder as AudioContentViewHolder
        viewHolder.voiceMessageView.setAudioMessage(message)
        if (message.playStatus == AudioMessageManager.PLAY_STATUS_NOT_PLAY) {
            viewHolder.voiceMessageView.setProgressColor(ContextCompat.getColor(viewHolder.voiceMessageView.context, com.difft.android.base.R.color.t_info))
            viewHolder.voiceMessageView.setCursorColor(ContextCompat.getColor(viewHolder.voiceMessageView.context, com.difft.android.base.R.color.t_info))
        } else {
            viewHolder.voiceMessageView.setProgressColor(ContextCompat.getColor(viewHolder.voiceMessageView.context, com.difft.android.base.R.color.icon))
            viewHolder.voiceMessageView.setCursorColor(ContextCompat.getColor(viewHolder.voiceMessageView.context, com.difft.android.base.R.color.icon))
        }

        if (message.isConfidential()) {
            viewHolder.tvCover.visibility = View.VISIBLE
            viewHolder.tvCover.setOnClickListener {
                LinkTextUtils.findParentChatMessageItemView(viewHolder.tvCover)?.performClick()
            }
        } else {
            viewHolder.tvCover.visibility = View.GONE
            viewHolder.tvCover.setOnClickListener(null)
        }

        viewHolder.failView.visibility = if (message.shouldShowFail()) View.VISIBLE else View.GONE
    }

    override fun createViewHolder(viewGroup: ViewGroup): ContentViewHolder {
        return AudioContentViewHolder(
            viewGroup.findViewById(R.id.voice_message_view),
            viewGroup.findViewById(R.id.v_cover),
            viewGroup.findViewById(R.id.cl_fail),
        )
    }

    class AudioContentViewHolder(
        val voiceMessageView: VoiceMessageView,
        val tvCover: TextView,
        val failView: ConstraintLayout
    ) : MessageContentAdapter.ContentViewHolder()
}