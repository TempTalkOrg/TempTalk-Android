package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatItemForwardHistoryBinding
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import difft.android.messageserialization.model.Forward

/**
 * 获取转发消息的预览文本
 */
internal fun getForwardText(context: Context, forward: Forward): String {
    return if (!forward.forwards.isNullOrEmpty()) {
        context.getString(R.string.chat_message_chat_history)
    } else if (forward.attachments?.isNotEmpty() == true) {
        context.getString(R.string.chat_message_attachment)
    } else if (forward.card != null) {
        forward.card?.content ?: ""
    } else {
        forward.text ?: ""
    }
}

/**
 * 合并转发消息的预览列表 Adapter
 */
class ForwardMessagesAdapter(
    private val contactorCache: MessageContactsCacheUtil
) : ListAdapter<Forward, ForwardMessagesItemViewHolder>(
    object : DiffUtil.ItemCallback<Forward>() {
        override fun areItemsTheSame(oldItem: Forward, newItem: Forward): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Forward, newItem: Forward): Boolean =
            oldItem == newItem
    }) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ForwardMessagesItemViewHolder {
        return ForwardMessagesItemViewHolder(parent)
    }

    override fun onBindViewHolder(holder: ForwardMessagesItemViewHolder, position: Int) {
        val data = getItem(position)
        holder.setContent(data, contactorCache)
    }
}

/**
 * 合并转发消息的预览列表 ViewHolder
 */
class ForwardMessagesItemViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    ChatItemForwardHistoryBinding.inflate(inflater, parentView, false).root
}) {
    private var binding: ChatItemForwardHistoryBinding =
        ChatItemForwardHistoryBinding.bind(itemView)

    @SuppressLint("SetTextI18n")
    fun setContent(forward: Forward, contactorCache: MessageContactsCacheUtil) {
        val text = getForwardText(binding.textContent.context, forward)
        // 从缓存中获取联系人信息
        val author = contactorCache.getContactor(forward.author)
        binding.textContent.text = if (author != null) {
            "${author.getDisplayNameWithoutRemarkForUI()}: $text"
        } else {
            "${forward.author.formatBase58Id()}: $text"
        }
    }
}