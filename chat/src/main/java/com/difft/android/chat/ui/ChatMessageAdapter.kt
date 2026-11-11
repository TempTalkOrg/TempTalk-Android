package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.chat.compose.SelectMessageState
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.ConfidentialPlaceholderChatMessage
import com.difft.android.chat.message.EncryptionHeaderChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.ui.theme.ChatBubbleGradient
import com.difft.android.chat.ui.theme.ChatThemeSetting
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isPlayableVideo
import org.difft.app.database.models.ContactorModel







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

            override fun getChangePayload(oldItem: ChatMessage, newItem: ChatMessage): Any? =
                changePayloadFor(oldItem, newItem)
        }) {

    companion object {
        
        const val PAYLOAD_SELECTION = "payload_selection"
        const val PAYLOAD_HIGHLIGHT = "payload_highlight"
        const val PAYLOAD_TRANSLATE = "payload_translate"

        
        const val PAYLOAD_WIDTH = "payload_width"

        


        internal fun changePayloadFor(oldItem: ChatMessage, newItem: ChatMessage): Any? {
            if (oldItem !is TextChatMessage || newItem !is TextChatMessage) return null
            return if (oldItem.contentEqualsIgnoringTranslate(newItem)) PAYLOAD_TRANSLATE else null
        }

        



        internal fun partialPayloadsFor(payloads: List<Any>, isMessageHolder: Boolean): Set<String> {
            if (payloads.isEmpty() || !isMessageHolder) return emptySet()
            return PARTIAL_PAYLOADS.filterTo(mutableSetOf()) { it in payloads }
        }

        private val PARTIAL_PAYLOADS = listOf(PAYLOAD_SELECTION, PAYLOAD_HIGHLIGHT, PAYLOAD_TRANSLATE, PAYLOAD_WIDTH)

        
        private const val CONTENT_TYPE_TEXT = 0
        private const val CONTENT_TYPE_IMAGE = 1
        private const val CONTENT_TYPE_VIDEO = 2
        private const val CONTENT_TYPE_AUDIO = 3
        private const val CONTENT_TYPE_ATTACH = 4
        private const val CONTENT_TYPE_CONTACT = 5
        private const val CONTENT_TYPE_MULTI_FORWARD = 6
        

        private const val MINE_OFFSET = 0
        private const val OTHERS_OFFSET = 100

        
        const val VIEW_TYPE_MINE_TEXT = MINE_OFFSET + CONTENT_TYPE_TEXT
        const val VIEW_TYPE_MINE_IMAGE = MINE_OFFSET + CONTENT_TYPE_IMAGE
        const val VIEW_TYPE_MINE_VIDEO = MINE_OFFSET + CONTENT_TYPE_VIDEO
        const val VIEW_TYPE_MINE_AUDIO = MINE_OFFSET + CONTENT_TYPE_AUDIO
        const val VIEW_TYPE_MINE_ATTACH = MINE_OFFSET + CONTENT_TYPE_ATTACH
        const val VIEW_TYPE_MINE_CONTACT = MINE_OFFSET + CONTENT_TYPE_CONTACT
        const val VIEW_TYPE_MINE_MULTI_FORWARD = MINE_OFFSET + CONTENT_TYPE_MULTI_FORWARD

        const val VIEW_TYPE_OTHERS_TEXT = OTHERS_OFFSET + CONTENT_TYPE_TEXT
        const val VIEW_TYPE_OTHERS_IMAGE = OTHERS_OFFSET + CONTENT_TYPE_IMAGE
        const val VIEW_TYPE_OTHERS_VIDEO = OTHERS_OFFSET + CONTENT_TYPE_VIDEO
        const val VIEW_TYPE_OTHERS_AUDIO = OTHERS_OFFSET + CONTENT_TYPE_AUDIO
        const val VIEW_TYPE_OTHERS_ATTACH = OTHERS_OFFSET + CONTENT_TYPE_ATTACH
        const val VIEW_TYPE_OTHERS_CONTACT = OTHERS_OFFSET + CONTENT_TYPE_CONTACT
        const val VIEW_TYPE_OTHERS_MULTI_FORWARD = OTHERS_OFFSET + CONTENT_TYPE_MULTI_FORWARD

        const val VIEW_TYPE_NOTIFY = 200  
        const val VIEW_TYPE_SCREENSHOT = 201
        const val VIEW_TYPE_CONFIDENTIAL_PLACEHOLDER = 202  
        const val VIEW_TYPE_E2EE_HEADER = 203  

        private val contentTypeConfigs = mapOf(
            CONTENT_TYPE_TEXT to (R.layout.chat_item_content_text to TextContentBinder),
            CONTENT_TYPE_IMAGE to (R.layout.chat_item_content_image to ImageContentBinder),
            CONTENT_TYPE_VIDEO to (R.layout.chat_item_content_image to ImageContentBinder),
            CONTENT_TYPE_AUDIO to (R.layout.chat_item_content_audio to AudioContentBinder),
            CONTENT_TYPE_ATTACH to (R.layout.chat_item_content_attach to AttachContentBinder),
            CONTENT_TYPE_CONTACT to (R.layout.chat_item_content_contact to ContactContentBinder),
            CONTENT_TYPE_MULTI_FORWARD to (R.layout.chat_item_content_multi_forward to MultiForwardContentBinder)
        )

        private fun isMineType(viewType: Int): Boolean = viewType < OTHERS_OFFSET
        private fun getContentType(viewType: Int): Int = viewType % OTHERS_OFFSET

    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)

        if (message is EncryptionHeaderChatMessage) {
            return VIEW_TYPE_E2EE_HEADER
        }

        if (message is ConfidentialPlaceholderChatMessage) {
            return VIEW_TYPE_CONFIDENTIAL_PLACEHOLDER
        }

        if (message is NotifyChatMessage) {
            return VIEW_TYPE_NOTIFY
        }

        if (message is TextChatMessage) {
            
            if (message.isScreenShotMessage) {
                return VIEW_TYPE_SCREENSHOT
            }

            val forwardsSize = message.forwardContext?.forwards?.size ?: 0
            if (forwardsSize > 1) {
                return if (message.isMine) VIEW_TYPE_MINE_MULTI_FORWARD else VIEW_TYPE_OTHERS_MULTI_FORWARD
            }

            
            if (forwardsSize == 1) {
                val forwardAttachment = message.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
                return when {
                    forwardAttachment?.isImage() == true -> {
                        if (message.isMine) VIEW_TYPE_MINE_IMAGE else VIEW_TYPE_OTHERS_IMAGE
                    }

                    
                    
                    forwardAttachment?.isPlayableVideo() == true -> {
                        if (message.isMine) VIEW_TYPE_MINE_VIDEO else VIEW_TYPE_OTHERS_VIDEO
                    }

                    forwardAttachment?.isAudioMessage() == true || forwardAttachment?.isAudioFile() == true -> {
                        if (message.isMine) VIEW_TYPE_MINE_AUDIO else VIEW_TYPE_OTHERS_AUDIO
                    }

                    forwardAttachment != null -> {
                        if (message.isMine) VIEW_TYPE_MINE_ATTACH else VIEW_TYPE_OTHERS_ATTACH
                    }

                    else -> {
                        if (message.isMine) VIEW_TYPE_MINE_TEXT else VIEW_TYPE_OTHERS_TEXT
                    }
                }
            }

            if (message.isAttachmentMessage()) {
                return when {
                    message.attachment?.isImage() == true -> {
                        if (message.isMine) VIEW_TYPE_MINE_IMAGE else VIEW_TYPE_OTHERS_IMAGE
                    }

                    message.attachment?.isPlayableVideo() == true -> {
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
                return if (message.isMine) VIEW_TYPE_MINE_CONTACT else VIEW_TYPE_OTHERS_CONTACT
            } else {
                return if (message.isMine) VIEW_TYPE_MINE_TEXT else VIEW_TYPE_OTHERS_TEXT
            }
        }

        return if (message.isMine) VIEW_TYPE_MINE_TEXT else VIEW_TYPE_OTHERS_TEXT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        
        if (viewType == VIEW_TYPE_E2EE_HEADER) {
            return ChatMessageViewHolder.Notify(
                parentView = parent,
                contentLayoutRes = R.layout.chat_item_content_e2ee_header,
                contentBinder = E2eeHeaderContentBinder,
            )
        }

        if (viewType == VIEW_TYPE_NOTIFY) {
            return ChatMessageViewHolder.Notify(
                parentView = parent,
                contentLayoutRes = R.layout.chat_item_content_notify,
                contentBinder = NotifyContentBinder()
            )
        }

        
        if (viewType == VIEW_TYPE_SCREENSHOT) {
            return ChatMessageViewHolder.Notify(
                parentView = parent,
                contentLayoutRes = R.layout.chat_item_content_notify,
                contentBinder = ScreenShotContentBinder
            )
        }

        
        if (viewType == VIEW_TYPE_CONFIDENTIAL_PLACEHOLDER) {
            return ChatMessageViewHolder.Notify(
                parentView = parent,
                contentLayoutRes = R.layout.chat_item_content_notify,
                contentBinder = ConfidentialPlaceholderContentBinder
            )
        }

        val isMine = isMineType(viewType)
        val contentType = getContentType(viewType)

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

        data.editMode = selectMessageState.editModel
        data.selectedStatus = data.id in selectMessageState.selectedMessageIds

        
        
        
        val partial = partialPayloadsFor(payloads, holder is ChatMessageViewHolder.Message)
        if (partial.isNotEmpty() && holder is ChatMessageViewHolder.Message) {
            if (PAYLOAD_SELECTION in partial) {
                holder.bindSelectionOnly(data) { id, selected -> onSelectedMessage(id, selected) }
            }
            if (PAYLOAD_HIGHLIGHT in partial) {
                holder.bindHighlightOnly(data, mHighlightItemIds)
            }
            if (PAYLOAD_TRANSLATE in partial) {
                holder.bindTranslateOnly(data) { message -> onTranslateRetry(message) }
            }
            if (PAYLOAD_WIDTH in partial) {
                holder.bindWidthOnly(data, contactorCache, shouldSaveToPhotos)
            }
            return
        }

        
        val callbacks = when (holder) {
            is ChatMessageViewHolder.Notify -> {
                MessageCallbacks.NotifyInteraction(onE2eeHeaderClick = { onE2eeHeaderClick() })
            }

            is ChatMessageViewHolder.Message -> {
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
                    onTranslateRetry = { message -> onTranslateRetry(message) }
                )
            }

            else -> {
                MessageCallbacks.NotifyInteraction()
            }
        }

        
        if (holder is ChatMessageViewHolder.Message) holder.bubble.applyChatTheme(ChatThemeSetting.current, bubbleGradient)
        holder.bind(data, callbacks, mHighlightItemIds, contactorCache, shouldSaveToPhotos)
    }

    



    var bubbleGradient: ChatBubbleGradient? = null

    override fun onViewRecycled(holder: ChatMessageViewHolder) {
        super.onViewRecycled(holder)
        
        if (holder is ChatMessageViewHolder.Message) {
            holder.clearHighlight()
            holder.clearQuoteThumbnail()
        }
    }

    private var mHighlightItemIds: ArrayList<Long>? = null

    




    fun highlightItem(ids: ArrayList<Long>) {
        mHighlightItemIds = ids
        
        ids.forEach { timeStamp ->
            val position = currentList.indexOfFirst { it.timeStamp == timeStamp }
            if (position >= 0) {
                notifyItemChanged(position, PAYLOAD_HIGHLIGHT)
            }
        }
    }

    
    private var selectMessageState = SelectMessageState(false, emptySet(), 0)

    
    


    var shouldSaveToPhotos: Boolean = false

    






    @SuppressLint("NotifyDataSetChanged")
    fun updateSelectionState(newState: SelectMessageState) {
        if (selectMessageState == newState) return

        val oldState = selectMessageState
        selectMessageState = newState

        
        if (oldState.editModel != newState.editModel) {
            notifyDataSetChanged()
            return
        }

        
        val addedIds = newState.selectedMessageIds - oldState.selectedMessageIds
        val removedIds = oldState.selectedMessageIds - newState.selectedMessageIds
        val changedIds = addedIds + removedIds

        changedIds.forEach { messageId ->
            val position = currentList.indexOfFirst { it.id == messageId }
            if (position >= 0) {
                notifyItemChanged(position, PAYLOAD_SELECTION)
            }
        }
    }

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

    
    abstract fun onTranslateRetry(message: TextChatMessage)

    open fun onSelectedMessage(messageId: String, selected: Boolean) = Unit

    
    
    
    open fun onE2eeHeaderClick() = Unit
}