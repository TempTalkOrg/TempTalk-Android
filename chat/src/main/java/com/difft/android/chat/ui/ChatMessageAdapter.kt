package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.difft.android.chat.R
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isAttachmentMessage
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import org.difft.app.database.models.ContactorModel

/**
 * 聊天消息 Adapter
 *
 * 负责管理和展示聊天消息列表，支持多种消息类型：
 * - 文本、图片、视频、语音、附件、联系人卡片
 * - 通知消息
 *
 * @param forWhat 聊天对象
 * @param contactorCache 联系人缓存实例（页面级，跟随ViewModel生命周期）
 */
abstract class ChatMessageAdapter(
    private val forWhat: For? = null,
    private val contactorCache: MessageContactsCacheUtil
) :
    ListAdapter<ChatMessage, ChatMessageViewHolder>(
        object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem == newItem
            }
        }) {

    companion object {
        // 消息内容类型定义（0-99）
        private const val CONTENT_TYPE_TEXT = 0
        private const val CONTENT_TYPE_IMAGE = 1
        private const val CONTENT_TYPE_VIDEO = 2
        private const val CONTENT_TYPE_AUDIO = 3
        private const val CONTENT_TYPE_ATTACH = 4
        private const val CONTENT_TYPE_CONTACT = 5
        // 预留 6-99 用于未来扩展

        // ViewType 计算偏移量
        private const val MINE_OFFSET = 0
        private const val OTHERS_OFFSET = 100

        // ViewType = Mine/Others 偏移量 + 内容类型
        const val VIEW_TYPE_MINE_TEXT = MINE_OFFSET + CONTENT_TYPE_TEXT        // 0
        const val VIEW_TYPE_MINE_IMAGE = MINE_OFFSET + CONTENT_TYPE_IMAGE      // 1
        const val VIEW_TYPE_MINE_VIDEO = MINE_OFFSET + CONTENT_TYPE_VIDEO      // 2
        const val VIEW_TYPE_MINE_AUDIO = MINE_OFFSET + CONTENT_TYPE_AUDIO      // 3
        const val VIEW_TYPE_MINE_ATTACH = MINE_OFFSET + CONTENT_TYPE_ATTACH    // 4
        const val VIEW_TYPE_MINE_CONTACT = MINE_OFFSET + CONTENT_TYPE_CONTACT  // 5

        const val VIEW_TYPE_OTHERS_TEXT = OTHERS_OFFSET + CONTENT_TYPE_TEXT       // 100
        const val VIEW_TYPE_OTHERS_IMAGE = OTHERS_OFFSET + CONTENT_TYPE_IMAGE     // 101
        const val VIEW_TYPE_OTHERS_VIDEO = OTHERS_OFFSET + CONTENT_TYPE_VIDEO     // 102
        const val VIEW_TYPE_OTHERS_AUDIO = OTHERS_OFFSET + CONTENT_TYPE_AUDIO     // 103
        const val VIEW_TYPE_OTHERS_ATTACH = OTHERS_OFFSET + CONTENT_TYPE_ATTACH   // 104
        const val VIEW_TYPE_OTHERS_CONTACT = OTHERS_OFFSET + CONTENT_TYPE_CONTACT // 105

        const val VIEW_TYPE_NOTIFY = 200  // 调整到 200，避免冲突

        // 内容类型配置表（layout + binder）
        private val contentTypeConfigs = mapOf(
            CONTENT_TYPE_TEXT to (R.layout.chat_item_content_text to TextContentBinder),
            CONTENT_TYPE_IMAGE to (R.layout.chat_item_content_image to ImageContentBinder),
            CONTENT_TYPE_VIDEO to (R.layout.chat_item_content_image to ImageContentBinder),
            CONTENT_TYPE_AUDIO to (R.layout.chat_item_content_audio to AudioContentBinder),
            CONTENT_TYPE_ATTACH to (R.layout.chat_item_content_attach to AttachContentBinder),
            CONTENT_TYPE_CONTACT to (R.layout.chat_item_content_contact to ContactContentBinder)
        )

        // 解析 ViewType
        private fun isMineType(viewType: Int): Boolean = viewType < OTHERS_OFFSET
        private fun getContentType(viewType: Int): Int = viewType % OTHERS_OFFSET
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)

        // 通知消息
        if (message is NotifyChatMessage) {
            return VIEW_TYPE_NOTIFY
        }

        // 判断消息内容类型
        if (message is TextChatMessage) {
            if (message.isAttachmentMessage()) {
                // 附件消息
                return when {
                    message.attachment?.isImage() == true -> {
                        if (message.isMine) VIEW_TYPE_MINE_IMAGE else VIEW_TYPE_OTHERS_IMAGE
                    }

                    message.attachment?.isVideo() == true -> {
                        if (message.isMine) VIEW_TYPE_MINE_VIDEO else VIEW_TYPE_OTHERS_VIDEO
                    }

                    message.attachment?.isAudioMessage() == true || message.attachment?.isAudioFile() == true -> {
                        if (message.isMine) VIEW_TYPE_MINE_AUDIO else VIEW_TYPE_OTHERS_AUDIO
                    }

                    else -> {
                        if (message.isMine) VIEW_TYPE_MINE_ATTACH else VIEW_TYPE_OTHERS_ATTACH
                    }
                }
            } else if (!message.sharedContacts.isNullOrEmpty()) {
                // 联系人卡片
                return if (message.isMine) VIEW_TYPE_MINE_CONTACT else VIEW_TYPE_OTHERS_CONTACT
            } else {
                // 文本消息
                return if (message.isMine) VIEW_TYPE_MINE_TEXT else VIEW_TYPE_OTHERS_TEXT
            }
        }

        // 默认文本消息
        return if (message.isMine) VIEW_TYPE_MINE_TEXT else VIEW_TYPE_OTHERS_TEXT
    }

    /**
     * 创建消息 ViewHolder
     *
     * 根据 ViewType 创建对应的 ViewHolder：
     * - 通知消息：创建 Notify ViewHolder
     * - 普通消息：根据内容类型和发送者创建 Message ViewHolder
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        // 通知消息
        if (viewType == VIEW_TYPE_NOTIFY) {
            return ChatMessageViewHolder.Notify(
                parentView = parent,
                contentLayoutRes = R.layout.chat_item_content_notify,
                contentBinder = NotifyContentBinder()
            )
        }

        // 普通消息：解析 ViewType 获取消息类型和发送者
        val isMine = isMineType(viewType)
        val contentType = getContentType(viewType)

        // 从配置表获取对应的 layout 和 binder
        val (contentLayoutRes, contentBinder) = contentTypeConfigs[contentType]
            ?: contentTypeConfigs[CONTENT_TYPE_TEXT]!!

        return ChatMessageViewHolder.Message(
            parentView = parent,
            isMine = isMine,
            contentLayoutRes = contentLayoutRes,
            contentBinder = contentBinder,
            forWhat = forWhat
        )
    }

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }

    override fun onBindViewHolder(
        holder: ChatMessageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val data = getItem(position)

        // 根据 ViewHolder 类型创建对应的 callbacks
        val callbacks = when (holder) {
            is ChatMessageViewHolder.Notify -> {
                // Notify 使用 NotifyInteraction（目前为空，未来可扩展）
                MessageCallbacks.NotifyInteraction()
            }

            is ChatMessageViewHolder.Message -> {
                // Message 使用 MessageInteraction
                MessageCallbacks.MessageInteraction(
                    onItemClick = { rootView, message -> onItemClick(rootView, message) },
                    onItemLongClick = { rootView, message -> onItemLongClick(rootView, message) },
                    onAvatarClicked = { contactor -> onAvatarClicked(contactor) },
                    onAvatarLongClicked = { contactor -> onAvatarLongClicked(contactor) },
                    onQuoteClicked = { quote -> onQuoteClicked(quote) },
                    onReactionClick = { message, emoji, remove, originTimeStamp ->
                        onReactionClick(message, emoji, remove, originTimeStamp)
                    },
                    onReactionLongClick = { message, emoji -> onReactionLongClick(message, emoji) },
                    onSelectPinnedMessage = { id, selected -> onSelectedMessage(id, selected) },
                    onSendStatusClicked = { rootView, message -> onSendStatusClicked(rootView, message) }
                )
            }

            else -> {
                // 默认使用 NotifyInteraction（兜底）
                MessageCallbacks.NotifyInteraction()
            }
        }

        // 使用简化的 bind 方法，highlightItemIds 作为独立参数，传递 contactorCache
        holder.bind(data, callbacks, mHighlightItemIds, contactorCache)
    }

    private var mHighlightItemIds: ArrayList<Long>? = null

    @SuppressLint("NotifyDataSetChanged")
    fun highlightItem(ids: ArrayList<Long>) {
        mHighlightItemIds = ids
        notifyDataSetChanged()
    }

    // ========== 抽象方法（子类实现） ==========
    abstract fun onItemClick(rootView: View, data: ChatMessage)
    abstract fun onItemLongClick(rootView: View, data: ChatMessage)
    abstract fun onAvatarClicked(contactor: ContactorModel?)
    abstract fun onAvatarLongClicked(contactor: ContactorModel?)
    abstract fun onQuoteClicked(quote: Quote)
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