package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.difft.android.chat.R
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.widget.VoiceMessageView
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import org.difft.app.database.models.ContactorModel

abstract class ChatMessageAdapter(private val forWhat: For? = null) : ListAdapter<ChatMessage, ChatMessageViewHolder>(
    object : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }) {

    companion object {
        const val VIEW_TYPE_MINE = 0b00000001
        const val VIEW_TYPE_OTHERS = VIEW_TYPE_MINE shl 1
        const val VIEW_TYPE_TEXT = VIEW_TYPE_MINE shl 2
        const val VIEW_TYPE_IMAGE = VIEW_TYPE_MINE shl 3
        const val VIEW_TYPE_VIDEO = VIEW_TYPE_MINE shl 4
        const val VIEW_TYPE_ATTACH = VIEW_TYPE_MINE shl 5
        const val VIEW_TYPE_NOTIFY = VIEW_TYPE_MINE shl 6
        const val VIEW_TYPE_AUDIO = VIEW_TYPE_MINE shl 8
        const val VIEW_TYPE_CONTACT = VIEW_TYPE_MINE shl 9
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        val mineOthers = if (message.isMine) VIEW_TYPE_MINE else VIEW_TYPE_OTHERS
        val type = if (message is TextChatMessage) {
            if (message.isAttachmentMessage()) {
                if (message.attachment?.isImage() == true) {
                    VIEW_TYPE_IMAGE
                } else if (message.attachment?.isVideo() == true) {
                    VIEW_TYPE_VIDEO
                } else if (message.attachment?.isAudioMessage() == true || message.attachment?.isAudioFile() == true) { // Check if the message is an audio message
                    VIEW_TYPE_AUDIO
                } else {
                    VIEW_TYPE_ATTACH
                }
            } else if (!message.sharedContacts.isNullOrEmpty()) {
                VIEW_TYPE_CONTACT
            } else {
                VIEW_TYPE_TEXT
            }
        } else if (message is NotifyChatMessage) {
            VIEW_TYPE_NOTIFY
        } else {
            VIEW_TYPE_TEXT
        }
        return mineOthers or type
    }

    private fun findViewHolderFactory(viewType: Int): ChatMessageViewHolder.Factory {
        return if ((viewType and VIEW_TYPE_NOTIFY) == VIEW_TYPE_NOTIFY) {
            ChatMessageViewHolder.Notify.Factory()
        } else {
            if ((viewType and VIEW_TYPE_MINE) == VIEW_TYPE_MINE) {
                ChatMessageViewHolder.Mine.Factory()
            } else {
                ChatMessageViewHolder.Others.Factory()
            }
        }
    }

    private fun findContentAdapter(viewType: Int): MessageContentAdapter {
        if ((viewType and VIEW_TYPE_TEXT) == VIEW_TYPE_TEXT) {
            return object : TextContentAdapter() {
                override fun onContentViewBound(
                    message: ChatMessage,
                    viewHolder: ContentViewHolder
                ) {
                    super.onContentViewBound(message, viewHolder)

                    onMessageViewBound(message)
                }
            }
        } else if ((viewType and VIEW_TYPE_IMAGE) == VIEW_TYPE_IMAGE) {
            return object : ImageContentAdapter() {
                override fun onContentViewBound(
                    message: ChatMessage,
                    viewHolder: ContentViewHolder
                ) {
                    super.onContentViewBound(message, viewHolder)

                    onMessageViewBound(message)
                }
            }
        } else if ((viewType and VIEW_TYPE_AUDIO) == VIEW_TYPE_AUDIO) {
            return object : AudioContentAdapter() {
                override fun onContentViewBound(
                    message: ChatMessage,
                    viewHolder: ContentViewHolder
                ) {
                    super.onContentViewBound(message, viewHolder)

                    onMessageViewBound(message)
                }
            }
        } else if ((viewType and VIEW_TYPE_VIDEO) == VIEW_TYPE_VIDEO) {
            return object : ImageContentAdapter() {
                override fun onContentViewBound(
                    message: ChatMessage,
                    viewHolder: ContentViewHolder
                ) {
                    super.onContentViewBound(message, viewHolder)

                    onMessageViewBound(message)
                }
            }
        } else if ((viewType and VIEW_TYPE_ATTACH) == VIEW_TYPE_ATTACH) {
            return object : AttachContentAdapter() {
                override fun onContentViewBound(
                    message: ChatMessage,
                    viewHolder: ContentViewHolder
                ) {
                    super.onContentViewBound(message, viewHolder)

                    onMessageViewBound(message)
                }
            }
        } else if ((viewType and VIEW_TYPE_CONTACT) == VIEW_TYPE_CONTACT) {
            return object : ContactContentAdapter() {
                override fun onContentViewBound(
                    message: ChatMessage,
                    viewHolder: ContentViewHolder
                ) {
                    super.onContentViewBound(message, viewHolder)

                    onMessageViewBound(message)
                }
            }
        } else if ((viewType and VIEW_TYPE_NOTIFY) == VIEW_TYPE_NOTIFY) {
            return object : NotifyContentAdapter() {
                override fun onContactRequestAcceptClicked(chatMessage: NotifyChatMessage) {
                    onContactAcceptClicked(chatMessage)
                }

                override fun onSendFriendRequestClicked(chatMessage: NotifyChatMessage) {
                    onFriendRequestClicked(chatMessage)
                }

                override fun onPinClick(chatMessage: NotifyChatMessage) {
                    onPinClicked(chatMessage)
                }

                override fun onContentViewBound(
                    message: ChatMessage,
                    viewHolder: ContentViewHolder
                ) {
                    super.onContentViewBound(message, viewHolder)

                    onMessageViewBound(message)
                }
            }
        } else {
            return object : TextContentAdapter() {
                override fun onContentViewBound(
                    message: ChatMessage,
                    viewHolder: ContentViewHolder
                ) {
                    super.onContentViewBound(message, viewHolder)

                    onMessageViewBound(message)
                }
            }
        }
    }

    open fun onMessageViewBound(message: ChatMessage) {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        return findViewHolderFactory(viewType).create(parent, findContentAdapter(viewType), forWhat)
    }

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int, payloads: MutableList<Any>) {
        val data = getItem(position)
        holder.bind(
            payloads,
            data,
            { rootView, message -> onItemClick(rootView, message) },
            { rootView, message -> onItemLongClick(rootView, message) },
            { contactor -> onAvatarClicked(contactor) },
            { contactor -> onAvatarLongClicked(contactor) },
            { quote -> onQuoteClicked(quote) },
            { message, emoji, remove, originTimeStamp -> onReactionClick(message, emoji, remove, originTimeStamp) },
            { message, emoji -> onReactionLongClick(message, emoji) },
            { id, selected -> onSelectedMessage(id, selected) },
            { rootView, message -> onSendStatusClicked(rootView, message) },
            mHighlightItemIds,
        )
    }

    private var mHighlightItemIds: ArrayList<Long>? = null

    @SuppressLint("NotifyDataSetChanged")
    fun highlightItem(ids: ArrayList<Long>) {
        mHighlightItemIds = ids
        notifyDataSetChanged()
    }

    override fun onViewRecycled(holder: ChatMessageViewHolder) {
        holder.itemView.findViewById<VoiceMessageView>(R.id.voice_message_view)?.unbind()
    }

    abstract fun onItemClick(rootView: View, data: ChatMessage)
    abstract fun onItemLongClick(rootView: View, data: ChatMessage)
    abstract fun onAvatarClicked(contactor: ContactorModel?)
    abstract fun onAvatarLongClicked(contactor: ContactorModel?)
    abstract fun onContactAcceptClicked(chatMessage: NotifyChatMessage)
    abstract fun onFriendRequestClicked(chatMessage: NotifyChatMessage)
    abstract fun onPinClicked(chatMessage: NotifyChatMessage)
    abstract fun onQuoteClicked(quote: Quote)
    abstract fun onRecallReEditClicked(chatMessage: TextChatMessage)
    abstract fun onReactionClick(
        message: ChatMessage,
        emoji: String,
        remove: Boolean,
        originTimeStamp: Long
    )

    abstract fun onReactionLongClick(
        message: ChatMessage,
        emoji: String,
    )

    open fun onSelectedMessage(messageId: String, selected: Boolean) = Unit
    abstract fun onSendStatusClicked(rootView: View, message: TextChatMessage)
}