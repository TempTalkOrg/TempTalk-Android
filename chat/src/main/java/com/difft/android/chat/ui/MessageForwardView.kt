package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.TextSizeUtil
import org.difft.app.database.getContactorFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.databinding.ChatItemForwardHistoryBinding
import com.difft.android.chat.databinding.ChatItemForwardZoneBinding
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.generateMessageFromForward
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import com.hi.dhl.binding.viewbind
import util.TimeUtils

class MessageForwardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes) {

    val binding: ChatItemForwardZoneBinding by viewbind(this)

    @SuppressLint("ClickableViewAccessibility")
    fun bindView(activity: Activity, message: TextChatMessage, itemView: View) {
        val forwardContext = message.forwardContext ?: return
        if (forwardContext.forwards != null) {
            binding.root.visibility = View.VISIBLE

//            binding.root.setOnClickListener {
////                        val title = binding.tvMultiTitle.text.toString()
//                ChatForwardMessageActivity.startActivity(activity, context.getString(R.string.chat_history), forwardContext)
//                RecentChatUtil.emitConfidentialRecipient(message)
//            }

//            binding.root.setOnLongClickListener {
//                itemView.performLongClick()
//                true
//            }

            if (forwardContext.forwards?.size == 1) {
                binding.singleForwardZone.visibility = View.VISIBLE
                binding.multiForwardZone.visibility = View.GONE

                binding.imageMessageView.visibility = View.GONE
                binding.voiceMessageView.visibility = View.GONE
                binding.attachContentView.visibility = View.GONE

                val forward = forwardContext.forwards?.firstOrNull() ?: return
                if (forward.attachments?.isNotEmpty() == true) {
                    val attachmentPointer = forward.attachments?.firstOrNull() ?: return

                    if (attachmentPointer.isImage() || attachmentPointer.isVideo()) {
                        val forwardMessage = generateMessageFromForward(forward) as TextChatMessage
                        binding.imageMessageView.visibility = View.VISIBLE
                        binding.imageMessageView.setupImageView(forwardMessage)
                    } else if (attachmentPointer.isAudioMessage() || attachmentPointer.isAudioFile()) {
                        val forwardMessage = generateMessageFromForward(forward) as TextChatMessage
                        binding.voiceMessageView.visibility = View.VISIBLE
                        binding.voiceMessageView.setAudioMessage(forwardMessage)
                    } else {
                        val forwardMessage = generateMessageFromForward(forward) as TextChatMessage
                        binding.attachContentView.visibility = View.VISIBLE
                        binding.attachContentView.setupAttachmentView(forwardMessage)
                    }
                }

                binding.tvForwardTime.text = TimeUtils.millis2String(forward.serverTimestampForUI, "yyyy/MM/dd HH:mm")

                binding.tvForwardContent.visibility = View.GONE
                binding.tvForwardContent.autoLinkMask = 0
                binding.tvForwardContent.movementMethod = null

                if (TextSizeUtil.isLager()) {
                    binding.tvForwardContent.textSize = 24f
                } else {
                    binding.tvForwardContent.textSize = 16f
                }

                if (!TextUtils.isEmpty(forward.text)) {
                    binding.tvForwardContent.visibility = View.VISIBLE
                    LinkTextUtils.setMarkdownToTextview(context, forward.text.toString(), binding.tvForwardContent, forward.mentions)
                } else {
                    binding.tvForwardContent.visibility = View.GONE
                }

                val author = wcdb.getContactorFromAllTable(forward.author)
                if (author != null) {
                    binding.tvForwardAuthor.text = author.getDisplayNameWithoutRemarkForUI()
                } else {
                    binding.tvForwardAuthor.text = forward.author
                }
            } else {
                binding.singleForwardZone.visibility = View.GONE
                binding.multiForwardZone.visibility = View.VISIBLE

                if (forwardContext.forwards?.firstOrNull()?.isFromGroup == true) {
                    binding.tvMultiTitle.text = context.getString(R.string.group_chat_history)
                } else {
                    val id = forwardContext.forwards?.firstOrNull()?.author ?: ""
                    val author = wcdb.getContactorFromAllTable(id)
                    if (author != null) {
                        binding.tvMultiTitle.text = context.getString(R.string.chat_history_for, author.getDisplayNameWithoutRemarkForUI())
                    } else {
                        binding.tvMultiTitle.text = context.getString(R.string.chat_history_for, id)
                    }
                }

                val adapter = ForwardMessagesAdapter()

                binding.rvForwardHistory.apply {
                    this.adapter = adapter
                    this.layoutManager = LinearLayoutManager(context)

                    this.setOnTouchListener { view, event ->
                        LinkTextUtils.findParentChatMessageItemView(view)?.onTouchEvent(event) ?: false
                    }
                }
                adapter.submitList(forwardContext.forwards?.take(5) ?: emptyList())
            }
        } else {
            binding.root.visibility = View.GONE
        }
    }
}

private fun getForwardText(context: Context, forward: Forward): String {
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

class ForwardMessagesAdapter : ListAdapter<Forward, ForwardMessagesItemViewHolder>(
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
        holder.setContent(data)
    }
}

class ForwardMessagesItemViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    ChatItemForwardHistoryBinding.inflate(inflater, parentView, false).root
}) {
    private var binding: ChatItemForwardHistoryBinding =
        ChatItemForwardHistoryBinding.bind(itemView)

    @SuppressLint("SetTextI18n")
    fun setContent(forward: Forward) {
        val text = getForwardText(binding.textContent.context, forward)
        val author = wcdb.getContactorFromAllTable(forward.author)
        if (author != null) {
            binding.textContent.text = author.getDisplayNameWithoutRemarkForUI() + ": " + text
        } else {
            binding.textContent.text = forward.author + "  " + text
        }
    }
}
