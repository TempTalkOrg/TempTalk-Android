package com.difft.android.chat.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.application
import com.difft.android.base.utils.dp
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.base.widget.setRightMargin
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.databinding.ChatItemChatMessageListNotifyBinding
import com.difft.android.chat.databinding.ChatItemChatMessageListTextMineBinding
import com.difft.android.chat.databinding.ChatItemChatMessageListTextOthersBinding
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.message.isConfidential
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.internal.managers.ViewComponentManager
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import org.difft.app.database.getContactorFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.util.Util
import util.TimeFormatter

abstract class ChatMessageViewHolder(itemView: View) : ViewHolder(itemView) {

    val myID: String by lazy {
        globalServices.myId
    }

    val language by lazy {
        LanguageUtils.getLanguage(application)
    }

    abstract fun bind(
        payloads: MutableList<Any>,
        message: ChatMessage,
        onItemClick: (rootView: View, message: ChatMessage) -> Unit,
        onItemLongClick: (rootView: View, message: ChatMessage) -> Unit,
        avatarClicked: (contactor: ContactorModel) -> Unit,
        avatarLongClicked: (contactor: ContactorModel) -> Unit,
        onQuoteClicked: (quote: Quote) -> Unit,
        onReactionClick: (message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) -> Unit,
        onReactionLongClick: (message: ChatMessage, emoji: String) -> Unit,
        onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?,
        onSendStatusClicked: (rootView: View, message: TextChatMessage) -> Unit,
        highlightItemIds: ArrayList<Long>?,
    )

    abstract class Factory {
        abstract fun create(
            parentView: ViewGroup,
            contentAdapter: MessageContentAdapter,
            forWhat: For?
        ): ChatMessageViewHolder
    }

    open class Mine(parentView: ViewGroup, private val contentAdapter: MessageContentAdapter, private val forWhat: For?) :
        ChatMessageViewHolder(run {
            val context = parentView.context
            val layoutInflater = LayoutInflater.from(context)
            val binding =
                ChatItemChatMessageListTextMineBinding.inflate(layoutInflater, parentView, false)
            layoutInflater.inflate(contentAdapter.layoutRes, binding.contentFrame, true)
            binding.root
        }) {

        private val binding: ChatItemChatMessageListTextMineBinding =
            ChatItemChatMessageListTextMineBinding.bind(itemView)

        private val contentViewHolder: MessageContentAdapter.ContentViewHolder =
            contentAdapter.createViewHolder(binding.contentFrame)

        private fun resetViewDefaults() {
            // Reset time view margin to default
            val timeView = binding.clMessageTime
            val timeLayoutParams = timeView.layoutParams as? LinearLayout.LayoutParams
            timeLayoutParams?.let {
                it.topMargin = (-26).dp
                timeView.layoutParams = it
            }

            // Reset text view padding to default
            val textView = binding.contentFrame.findViewById<TextView>(R.id.textView)
            textView?.setPaddingRelative(8.dp, 8.dp, 8.dp, 8.dp)

            // Reset forward text view padding to default (0 padding as per layout XML)
            val forwardTextView = binding.messageForwardView.findViewById<TextView>(R.id.tv_forward_content)
            forwardTextView?.setPaddingRelative(8.dp, 0, 8.dp, 8.dp)

            // Reset reactions view (FlowLayout) padding to default
            binding.reactionsView.setPaddingRelative(8.dp, 4.dp, 8.dp, 8.dp)
        }

        override fun bind(
            payloads: MutableList<Any>,
            message: ChatMessage,
            onItemClick: (rootView: View, message: ChatMessage) -> Unit,
            onItemLongClick: (rootView: View, message: ChatMessage) -> Unit,
            avatarClicked: (contactor: ContactorModel) -> Unit,
            avatarLongClicked: (contactor: ContactorModel) -> Unit,
            onQuoteClicked: (quote: Quote) -> Unit,
            onReactionClick: (message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) -> Unit,
            onReactionLongClick: (message: ChatMessage, emoji: String) -> Unit,
            onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?,
            onSendStatusClicked: (rootView: View, message: TextChatMessage) -> Unit,
            highlightItemIds: ArrayList<Long>?
        ) {
            if (message !is TextChatMessage) return

            // Reset default values for recycled views
            resetViewDefaults()

            binding.contentContainer.setOnLongClickListener {
                onItemLongClick(it.parent as ConstraintLayout, message)
                true
            }

            binding.contentContainer.setOnClickListener {
                onItemClick(it, message)
            }

            setupMessageTimeStyle(
                message = message,
                timeContainer = binding.clMessageTime,
                timeTextView = binding.textViewTime,
                sendStatusImageView = binding.ivSendStatus,
                readNumberTextView = binding.ivReadNumber
            )

            if (message.showNewMsgDivider) {
                binding.llNewMsgDivider.root.visibility = View.VISIBLE
            } else {
                binding.llNewMsgDivider.root.visibility = View.GONE
            }

            if (message.showDayTime) {
                binding.tvDayTime.visibility = View.VISIBLE
                binding.tvDayTime.text = TimeFormatter.getConversationDateHeaderString(binding.root.context, language, message.systemShowTimestamp)
            } else {
                binding.tvDayTime.visibility = View.GONE
            }

            if (message.showTime) {
                binding.textViewTime.visibility = View.VISIBLE
                binding.textViewTime.text = TimeFormatter.formatMessageTime(language.language, message.systemShowTimestamp)
            } else {
                binding.textViewTime.visibility = View.GONE
            }

            if (message.quote != null) {
                binding.quoteZone.visibility = View.VISIBLE
                binding.quoteAuthor.text = message.quote?.authorName
                binding.quoteText.text = message.quote?.text
                binding.quoteZone.setOnClickListener { message.quote?.let { onQuoteClicked(it) } }
            } else {
                binding.quoteZone.visibility = View.GONE
            }

            if (message.forwardContext == null) {
                binding.contentFrame.visibility = View.VISIBLE
                binding.clForwardContent.visibility = View.GONE
            } else {
                binding.contentFrame.visibility = View.GONE
                binding.clForwardContent.visibility = View.VISIBLE
                val context = if (binding.root.context is ViewComponentManager.FragmentContextWrapper) {
                    (binding.root.context as ViewComponentManager.FragmentContextWrapper).baseContext as Activity
                } else {
                    binding.root.context as Activity
                }
                binding.messageForwardView.bindView(context, message, binding.contentContainer)

                if (message.isConfidential()) {
                    binding.vForwardCover.visibility = View.VISIBLE
                    binding.vForwardCover.setOnClickListener {
                        binding.contentContainer.performClick()
                    }
                    binding.vForwardCover.setOnLongClickListener {
                        binding.contentContainer.performLongClick()
                        true
                    }
                } else {
                    binding.vForwardCover.visibility = View.GONE
                }
            }

            initReactionView(binding.root, binding.reactionsView, message, onReactionClick, onReactionLongClick)

            initTranslateView(message, binding.clTranslate, binding.pbTranslate, binding.tvTranslateContent)

            initSpeechToTextView(
                message,
                binding.clSpeechToText,
                binding.pbSpeechToText,
                binding.tvSpeechToTextContent,
                binding.ivSpeech2textServerTipIcon
            )

            binding.ivSpeech2textServerTipIcon.setOnClickListener {
                ComposeDialogManager.showMessageDialog(
                    context = binding.root.context,
                    title = ApplicationHelper.instance.getString(R.string.chat_message_action_voice2text_tip_title),
                    message = ApplicationHelper.instance.getString(R.string.chat_message_action_voice2text_tip_info),
                    confirmText = ApplicationHelper.instance.getString(R.string.chat_message_action_voice2text_tip_ok),
                    showCancel = false
                )
            }

            initSendAndReadStatus(message, onSendStatusClicked)

            binding.checkboxSelectForUnpin.setOnCheckedChangeListener(null) //must set null first, otherwise the listener will be triggered when setChecked
            binding.checkboxSelectForUnpin.isChecked = message.selectedStatus
            binding.checkboxSelectForUnpin.setOnCheckedChangeListener { _, b ->
                if (message.isConfidential()) {
                    binding.checkboxSelectForUnpin.isChecked = message.selectedStatus
                    ToastUtil.show(binding.root.context.getString(R.string.chat_confidential_can_not_select))
                } else {
                    if (b != message.selectedStatus) {
                        onSelectPinnedMessage?.invoke(message.id, b)
                    }
                }
            }
            binding.checkboxSelectForUnpin.visibility =
                if (message.editMode && message.attachment?.isAudioMessage() != true && message.attachment?.isAudioFile() != true) View.VISIBLE
                else if (message.editMode && (message.attachment?.isAudioMessage() == true || message.attachment?.isAudioFile() == true)) View.INVISIBLE
                else View.GONE
            contentAdapter.bindContentView(message, contentViewHolder)
        }

        private fun initSendAndReadStatus(message: TextChatMessage, onSendStatusClicked: (rootView: View, message: TextChatMessage) -> Unit) {
            binding.ivSendStatus.visibility = View.GONE
            binding.ivReadNumber.visibility = View.GONE
            binding.ivSendFail.visibility = View.GONE
            binding.ivSendStatus.updateLayoutParams<ConstraintLayout.LayoutParams> {
                width = 13.dp
                height = 13.dp
            }

            when (message.sendStatus) {
                SendType.Sending.rawValue -> {
                    binding.ivSendStatus.visibility = View.VISIBLE
                    binding.ivSendStatus.setImageResource(R.drawable.chat_icon_message_sending_new)
                    binding.ivSendStatus.updateLayoutParams<ConstraintLayout.LayoutParams> {
                        width = 8.dp
                        height = 8.dp
                    }
                }

                SendType.Sent.rawValue -> {
                    if (message.readStatus == 1) {
                        binding.ivSendStatus.visibility = View.VISIBLE
                        binding.ivSendStatus.setImageResource(R.drawable.chat_icon_message_read)
                    } else {
                        if (message.readContactNumber == 0) {
                            binding.ivSendStatus.visibility = View.VISIBLE
                            binding.ivSendStatus.setImageResource(R.drawable.chat_icon_message_sent_success)
                        } else {
                            binding.ivReadNumber.visibility = View.VISIBLE
                            binding.ivReadNumber.text = message.readContactNumber.toString()
                        }
                    }
                }

                SendType.SentFailed.rawValue -> {
                    binding.ivSendFail.visibility = View.VISIBLE
                    binding.ivSendFail.setOnClickListener {
                        onSendStatusClicked(binding.root, message)
                    }
                }

                else -> {
                    binding.ivSendStatus.visibility = View.GONE
                    binding.ivReadNumber.visibility = View.GONE
                    binding.ivSendFail.visibility = View.GONE
                }
            }

            binding.clMessageTime.setOnClickListener {
                onSendStatusClicked(binding.root, message)
            }
        }

        class Factory : ChatMessageViewHolder.Factory() {
            override fun create(parentView: ViewGroup, contentAdapter: MessageContentAdapter, forWhat: For?): Mine {
                return Mine(parentView, contentAdapter, forWhat)
            }
        }
    }

    open class Others(
        parentView: ViewGroup,
        private val contentAdapter: MessageContentAdapter,
        private val forWhat: For?,
    ) : ChatMessageViewHolder(run {
        val context = parentView.context
        val layoutInflater = LayoutInflater.from(context)
        val binding = ChatItemChatMessageListTextOthersBinding.inflate(layoutInflater, parentView, false)
        layoutInflater.inflate(contentAdapter.layoutRes, binding.contentFrame, true)
        contentAdapter.createViewHolder(binding.contentFrame)
        binding.root
    }) {

        private val binding: ChatItemChatMessageListTextOthersBinding =
            ChatItemChatMessageListTextOthersBinding.bind(itemView)

        private val contentViewHolder: MessageContentAdapter.ContentViewHolder =
            contentAdapter.createViewHolder(binding.contentFrame)

        private fun resetViewDefaults() {
            // Reset time view margin to default
            val timeView = binding.clMessageTime
            val timeLayoutParams = timeView.layoutParams as? LinearLayout.LayoutParams
            timeLayoutParams?.let {
                it.topMargin = (-26).dp
                timeView.layoutParams = it
            }

            // Reset text view padding to default
            val textView = binding.contentFrame.findViewById<TextView>(R.id.textView)
            textView?.setPaddingRelative(8.dp, 8.dp, 8.dp, 8.dp)

            // Reset forward text view padding to default (0 padding as per layout XML)
            val forwardTextView = binding.messageForwardView.findViewById<TextView>(R.id.tv_forward_content)
            forwardTextView?.setPaddingRelative(8.dp, 0, 8.dp, 8.dp)

            // Reset reactions view (FlowLayout) padding to default
            binding.reactionsView.setPaddingRelative(8.dp, 4.dp, 8.dp, 8.dp)
        }

        override fun bind(
            payloads: MutableList<Any>,
            message: ChatMessage,
            onItemClick: (rootView: View, message: ChatMessage) -> Unit,
            onItemLongClick: (rootView: View, message: ChatMessage) -> Unit,
            avatarClicked: (contactor: ContactorModel) -> Unit,
            avatarLongClicked: (contactor: ContactorModel) -> Unit,
            onQuoteClicked: (quote: Quote) -> Unit,
            onReactionClick: (message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) -> Unit,
            onReactionLongClick: (message: ChatMessage, emoji: String) -> Unit,
            onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?,
            onSendStatusClicked: (rootView: View, message: TextChatMessage) -> Unit,
            highlightItemIds: ArrayList<Long>?
        ) {
            if (message !is TextChatMessage) return

            // Reset default values for recycled views
            resetViewDefaults()

            binding.contentContainer.setOnLongClickListener {
                onItemLongClick(it.parent as ConstraintLayout, message)
                true
            }

            binding.contentContainer.setOnClickListener {
                onItemClick(it, message)
            }

            if (message.showName) {
                binding.clName.visibility = View.VISIBLE
                message.contactor?.let { contactor ->
                    binding.imageviewAvatar.setAvatar(
                        contactor,
                        letterTextSizeDp = 16
                    )
                    binding.imageviewAvatar.setOnClickListener { avatarClicked(contactor) }
                    binding.imageviewAvatar.setOnLongClickListener {
                        avatarLongClicked(contactor)
                        true
                    }
                }
                binding.textviewNickname.text = message.nickname
            } else {
                binding.clName.visibility = View.GONE
            }

            setupMessageTimeStyle(
                message = message,
                timeContainer = binding.clMessageTime,
                timeTextView = binding.textViewTime
            )

            if (message.showNewMsgDivider) {
                binding.llNewMsgDivider.root.visibility = View.VISIBLE
            } else {
                binding.llNewMsgDivider.root.visibility = View.GONE
            }

            if (message.showDayTime) {
                binding.tvDayTime.visibility = View.VISIBLE
                binding.tvDayTime.text = TimeFormatter.getConversationDateHeaderString(binding.root.context, language, message.systemShowTimestamp)
            } else {
                binding.tvDayTime.visibility = View.GONE
            }

            if (message.showTime) {
                binding.clMessageTime.visibility = View.VISIBLE
                binding.textViewTime.text = TimeFormatter.formatMessageTime(language.language, message.systemShowTimestamp)
            } else {
                binding.clMessageTime.visibility = View.GONE
            }
            if (message.quote != null) {
                binding.quoteZone.visibility = View.VISIBLE
                binding.quoteAuthor.text = message.quote?.authorName
                binding.quoteText.text = message.quote?.text
                binding.quoteZone.setOnClickListener { onQuoteClicked(message.quote!!) }
            } else {
                binding.quoteZone.visibility = View.GONE
            }

            if (message.forwardContext == null) {
                binding.contentFrame.visibility = View.VISIBLE
                binding.clForwardContent.visibility = View.GONE
            } else {
                binding.contentFrame.visibility = View.GONE
                binding.clForwardContent.visibility = View.VISIBLE
                val context = if (binding.root.context is ViewComponentManager.FragmentContextWrapper) {
                    (binding.root.context as ViewComponentManager.FragmentContextWrapper).baseContext as Activity
                } else {
                    binding.root.context as Activity
                }
                binding.messageForwardView.bindView(context, message, binding.contentContainer)

                if (message.isConfidential()) {
                    binding.vForwardCover.visibility = View.VISIBLE
                    binding.vForwardCover.setOnClickListener {
                        binding.contentContainer.performClick()
                    }
                    binding.vForwardCover.setOnLongClickListener {
                        binding.contentContainer.performLongClick()
                        true
                    }
                } else {
                    binding.vForwardCover.visibility = View.GONE
                }
            }

            initReactionView(binding.root, binding.reactionsView, message, onReactionClick, onReactionLongClick)

            initTranslateView(message, binding.clTranslate, binding.pbTranslate, binding.tvTranslateContent)

            initSpeechToTextView(
                message,
                binding.clSpeechToText,
                binding.pbSpeechToText,
                binding.tvSpeechToTextContent,
                binding.ivSpeech2textServerTipIcon
            )

            binding.ivSpeech2textServerTipIcon.setOnClickListener {
                ComposeDialogManager.showMessageDialog(
                    context = binding.root.context,
                    title = ApplicationHelper.instance.getString(R.string.chat_message_action_voice2text_tip_title),
                    message = ApplicationHelper.instance.getString(R.string.chat_message_action_voice2text_tip_info),
                    confirmText = ApplicationHelper.instance.getString(R.string.chat_message_action_voice2text_tip_ok),
                    showCancel = false
                )
            }

//            binding.root.setBackgroundColor(0)
            highlightItemIds?.let {
                if (it.contains(message.timeStamp)) {
                    binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, com.difft.android.base.R.color.bg2))
                    binding.root.postDelayed({
                        binding.root.setBackgroundColor(0)
                        highlightItemIds.remove(message.timeStamp)
                    }, 1000)
                }
            }
            binding.checkboxSelectForUnpin.setOnCheckedChangeListener(null) //must set null first, otherwise the listener will be triggered when setChecked
            binding.checkboxSelectForUnpin.isChecked = message.selectedStatus
            binding.checkboxSelectForUnpin.setOnCheckedChangeListener { _, b ->
                if (message.isConfidential()) {
                    binding.checkboxSelectForUnpin.isChecked = message.selectedStatus
                    ToastUtil.show(binding.root.context.getString(R.string.chat_confidential_can_not_select))
                } else {
                    if (b != message.selectedStatus) {
                        onSelectPinnedMessage?.invoke(message.id, b)
                    }
                }
            }
            binding.checkboxSelectForUnpin.visibility =
                if (message.editMode && message.attachment?.isAudioMessage() != true && message.attachment?.isAudioFile() != true) View.VISIBLE
                else if (message.editMode && (message.attachment?.isAudioMessage() == true || message.attachment?.isAudioFile() == true)) View.INVISIBLE
                else View.GONE

            contentAdapter.bindContentView(message, contentViewHolder)
        }

        class Factory : ChatMessageViewHolder.Factory() {
            override fun create(
                parentView: ViewGroup,
                contentAdapter: MessageContentAdapter,
                forWhat: For?
            ): ChatMessageViewHolder {
                return Others(parentView, contentAdapter, forWhat)
            }
        }

    }

    open class Notify(
        parentView: ViewGroup, private val contentAdapter:
        MessageContentAdapter
    ) : ChatMessageViewHolder(run {
        val context = parentView.context
        val layoutInflater = LayoutInflater.from(context)
        val binding = ChatItemChatMessageListNotifyBinding.inflate(layoutInflater, parentView, false)
        layoutInflater.inflate(contentAdapter.layoutRes, binding.contentFrame, true)
        contentAdapter.createViewHolder(binding.contentFrame)
        binding.root
    }) {
        private val binding: ChatItemChatMessageListNotifyBinding = ChatItemChatMessageListNotifyBinding.bind(itemView)

        private val contentViewHolder: MessageContentAdapter.ContentViewHolder =
            contentAdapter.createViewHolder(binding.contentFrame)

        override fun bind(
            payloads: MutableList<Any>,
            message: ChatMessage,
            onItemClick: (rootView: View, message: ChatMessage) -> Unit,
            onItemLongClick: (rootView: View, message: ChatMessage) -> Unit,
            avatarClicked: (contactor: ContactorModel) -> Unit,
            avatarLongClicked: (contactor: ContactorModel) -> Unit,
            onQuoteClicked: (quote: Quote) -> Unit,
            onReactionClick: (message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) -> Unit,
            onReactionLongClick: (message: ChatMessage, emoji: String) -> Unit,
            onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?,
            onSendStatusClicked: (rootView: View, message: TextChatMessage) -> Unit,
            highlightItemIds: ArrayList<Long>?
        ) {
            if (message.showNewMsgDivider) {
                binding.llNewMsgDivider.root.visibility = View.VISIBLE
            } else {
                binding.llNewMsgDivider.root.visibility = View.GONE
            }

            if (message.showDayTime) {
                binding.tvDayTime.visibility = View.VISIBLE
                binding.tvDayTime.text = TimeFormatter.getConversationDateHeaderString(binding.root.context, language, message.systemShowTimestamp)
            } else {
                binding.tvDayTime.visibility = View.GONE
            }
            contentAdapter.bindContentView(message, contentViewHolder)
        }

        class Factory : ChatMessageViewHolder.Factory() {
            override fun create(
                parentView: ViewGroup,
                contentAdapter: MessageContentAdapter,
                forWhat: For?
            ): ChatMessageViewHolder {
                return Notify(parentView, contentAdapter)
            }
        }

    }

    fun initReactionView(
        root: View,
        reactionsView: FlowLayout,
        message: TextChatMessage,
        onReactionClick: (message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) -> Unit,
        onReactionLongClick: (message: ChatMessage, emoji: String) -> Unit
    ) {
        if (message.reactions != null && !message.reactions.isNullOrEmpty()) {
            reactionsView.visibility = View.VISIBLE
            reactionsView.removeAllViews()

            val emojis = message.reactions?.groupBy { it.emoji }

            emojis?.forEach { reaction ->
                val reactionItem = LayoutInflater.from(reactionsView.context).inflate(R.layout.chat_item_reaction, reactionsView, false)
                reactionItem.findViewById<AppCompatTextView>(R.id.tv_emoji).text = reaction.key
                val reactions = reaction.value.distinctBy { it.uid }
                if (reactions.size == 1) {
                    val id = reactions[0].uid
                    val contact = wcdb.getContactorFromAllTable(id)
                    var text: String = contact?.getDisplayNameForUI() ?: id.formatBase58Id()
                    if (text.length > 10) {
                        text = text.substring(0, 10) + "..."
                    }
                    reactionItem.findViewById<AppCompatTextView>(R.id.tv_count).text = text
                } else {
                    reactionItem.findViewById<AppCompatTextView>(R.id.tv_count).text = reactions.size.toString()
                }
                reactionItem.isSelected = reactions.find { it.uid == myID } != null

                reactionItem.setOnClickListener {
                    onReactionClick(message, reaction.key, reactionItem.isSelected, reactions.find { it.uid == myID }?.originTimestamp ?: 0L)
                }
                reactionItem.setOnLongClickListener {
                    onReactionLongClick(message, reaction.key)
                    true
                }

                reactionsView.addView(reactionItem)
            }
        } else {
            reactionsView.visibility = View.GONE
        }
    }

    fun initTranslateView(
        message: TextChatMessage,
        clTranslate: ConstraintLayout,
        pbTranslate: ProgressBar,
        tvTranslateContent: AppCompatTextView
    ) {
        clTranslate.isVisible = false
        message.translateData?.let {
            clTranslate.isVisible = true
            pbTranslate.isVisible = false
            when (it.translateStatus) {
                TranslateStatus.Invisible -> {
                    clTranslate.isVisible = false
                }

                TranslateStatus.Translating -> {
                    pbTranslate.isVisible = true
                    tvTranslateContent.text = itemView.context.getString(R.string.chat_translating)
                }

                TranslateStatus.ShowEN -> {
                    showContent(tvTranslateContent, it.translatedContentEN ?: "")
                }

                TranslateStatus.ShowCN -> {
                    showContent(tvTranslateContent, it.translatedContentCN ?: "")
                }

                else -> {
                    clTranslate.isVisible = false
                }
            }
        }
    }

    fun initSpeechToTextView(
        message: TextChatMessage,
        clSpeechToText: ConstraintLayout,
        pbSpeechToText: ProgressBar,
        tvSpeechToTextContent: AppCompatTextView,
        ivSpeech2textServerTipIcon: ImageView,
    ) {
        clSpeechToText.isVisible = false
        ivSpeech2textServerTipIcon.isVisible = false
        message.speechToTextData?.let {
            clSpeechToText.isVisible = true
            pbSpeechToText.isVisible = false
            when (it.convertStatus) {
                SpeechToTextStatus.Invisible -> {
                    clSpeechToText.isVisible = false
                }

                SpeechToTextStatus.Converting -> {
                    pbSpeechToText.isVisible = true
                    tvSpeechToTextContent.text = itemView.context.getString(R.string.chat_speech_to_text)
                }

                SpeechToTextStatus.Show -> {
                    if (message.attachment?.flags == 1) { // 只在语音消息时展示speechToText内容
                        showContent(tvSpeechToTextContent, it.speechToTextContent ?: "")
                        ivSpeech2textServerTipIcon.isVisible = true
                    } else {
                        pbSpeechToText.isVisible = false
                        clSpeechToText.isVisible = false
                        ivSpeech2textServerTipIcon.isVisible = false
                    }
                }

                else -> {
                    clSpeechToText.isVisible = false
                    ivSpeech2textServerTipIcon.isVisible = false
                }
            }
        }
    }

    private fun showContent(tvTranslateContent: AppCompatTextView, content: String) {
        tvTranslateContent.text = content
        tvTranslateContent.setOnLongClickListener {
            Util.copyToClipboard(tvTranslateContent.context, content)
            true
        }
    }

    fun setupMessageTimeStyle(
        message: TextChatMessage,
        timeContainer: View,
        timeTextView: TextView,
        sendStatusImageView: ImageView? = null,
        readNumberTextView: TextView? = null
    ) {
        val shouldShowShadow = shouldShowMessageTimeShadow(message)

        val whiteColor = ContextCompat.getColor(timeContainer.context, com.difft.android.base.R.color.t_white)
        val thirdColor = ContextCompat.getColor(timeContainer.context, com.difft.android.base.R.color.t_third)

        if (shouldShowShadow) {
            // 显示阴影背景和白色文字
            timeContainer.setBackgroundResource(R.drawable.chat_message_item_time_bg)
            timeTextView.setTextColor(whiteColor)

            // 设置发送状态图标颜色
            sendStatusImageView?.imageTintList = ContextCompat.getColorStateList(timeContainer.context, com.difft.android.base.R.color.t_white)

            // 设置已读数量背景和文字颜色
            readNumberTextView?.apply {
                background = ResUtils.getDrawable(R.drawable.chat_read_number_bg_white)
                setTextColor(whiteColor)
            }

            timeContainer.setRightMargin(8.dp)
        } else {
            // 清除背景，使用默认颜色
            timeContainer.background = null
            timeTextView.setTextColor(thirdColor)

            // 设置发送状态图标颜色
            sendStatusImageView?.imageTintList = ContextCompat.getColorStateList(timeContainer.context, com.difft.android.base.R.color.t_third)

            // 设置已读数量背景和文字颜色
            readNumberTextView?.apply {
                background = ResUtils.getDrawable(R.drawable.chat_read_number_bg)
                setTextColor(thirdColor)
            }

            timeContainer.setRightMargin(0)
        }
    }

    private fun shouldShowMessageTimeShadow(message: TextChatMessage): Boolean {
        if (!message.reactions.isNullOrEmpty()) {
            return false
        }

        if (message.isConfidential() && message.message.isNullOrEmpty()) {
            return true
        }

        if (message.isAttachmentMessage() && message.attachment?.let { it.isImage() || it.isVideo() } == true) {
            return true
        }

        if (message.forwardContext?.forwards?.size == 1) {
            val forward = message.forwardContext?.forwards?.firstOrNull()
            if (forward?.attachments?.any { it.isImage() || it.isVideo() } == true) {
                return true
            }
        }

        return false
    }
}

abstract class MessageContentAdapter {
    abstract val layoutRes: Int

    abstract class ContentViewHolder

    fun bindContentView(message: ChatMessage, viewHolder: ContentViewHolder) {
        onBindContentView(message, viewHolder)

        onContentViewBound(message, viewHolder)
    }

    abstract fun onBindContentView(message: ChatMessage, contentViewHolder: ContentViewHolder)

    open fun onContentViewBound(message: ChatMessage, viewHolder: ContentViewHolder) {}

    abstract fun createViewHolder(viewGroup: ViewGroup): ContentViewHolder
}
