package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
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
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.bumptech.glide.Glide
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.application
import com.difft.android.base.utils.normalizeNewlines
import com.difft.android.base.utils.dp
import com.difft.android.base.utils.getLifecycleOwner
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.base.widget.setRightMargin
import com.difft.android.chat.widget.fileTypeIcon
import com.difft.android.chat.R
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.common.SendType
import com.difft.android.chat.databinding.ChatItemChatMessageListNotifyBinding
import com.difft.android.chat.databinding.ChatItemChatMessageListTextMineBinding
import com.difft.android.chat.databinding.ChatItemChatMessageListTextOthersBinding
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.EncryptionHeaderChatMessage
import com.difft.android.chat.message.NoticeAggregator
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.translate.ui.TranslateBlockBinder
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.message.generateMessageFromForward
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isLongText
import difft.android.messageserialization.model.isPlayableVideo
import difft.android.messageserialization.model.isWhitelistedVideoType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.attachment
import org.difft.app.database.forwardContext
import org.difft.app.database.wcdb
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.util.MediaUtil
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.util.QuoteThumbnailBinder
import com.difft.android.chat.util.Util
import com.difft.android.chat.util.isHostActivityAlive
import util.TimeFormatter
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import com.difft.android.base.widget.DifftCheckBoxView
import kotlin.math.roundToInt


sealed class MessageCallbacks {
    
    data class MessageInteraction(
        val onItemClick: (rootView: View, message: ChatMessage) -> Unit,
        val onItemLongClick: (rootView: View, message: ChatMessage) -> Unit,
        val onAvatarClicked: (contactor: ContactorModel) -> Unit,
        val onAvatarLongClicked: (contactor: ContactorModel) -> Unit,
        val onQuoteClicked: (quote: Quote) -> Unit,
        val onReactionClick: (message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) -> Unit,
        val onReactionLongClick: (message: ChatMessage, emoji: String) -> Unit,
        val onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?,
        val onTranslateRetry: (message: TextChatMessage) -> Unit
    ) : MessageCallbacks()

    
    data class NotifyInteraction(
        val placeholder: Unit = Unit,
        val onE2eeHeaderClick: (() -> Unit)? = null,
    ) : MessageCallbacks()
}


private const val REACTION_CHIP_RADIUS_DP = 14f

abstract class ChatMessageViewHolder(itemView: View) : ViewHolder(itemView) {

    val myID: String by lazy {
        globalServices.myId
    }

    val language by lazy {
        LanguageUtils.getLanguage(application)
    }

    







    abstract fun bind(
        message: ChatMessage,
        callbacks: MessageCallbacks,
        highlightItemIds: ArrayList<Long>? = null,
        contactorCache: MessageContactsCacheUtil,
        shouldSaveToPhotos: Boolean = false
    )

    






    class Message(
        private val parentView: ViewGroup,
        private val isMine: Boolean,
        contentLayoutRes: Int,
        private val contentBinder: ContentBinder,
        private val forWhat: For?
    ) : ChatMessageViewHolder(run {
        val context = parentView.context
        val layoutInflater = LayoutInflater.from(context)

        val itemView = if (isMine) {
            val binding = ChatItemChatMessageListTextMineBinding.inflate(layoutInflater, parentView, false)
            binding.contentContainer.sent = true
            layoutInflater.inflate(contentLayoutRes, binding.contentFrame, true)
            binding.root
        } else {
            val binding = ChatItemChatMessageListTextOthersBinding.inflate(layoutInflater, parentView, false)
            binding.contentContainer.avatarColumn = forWhat !is For.Account
            layoutInflater.inflate(contentLayoutRes, binding.contentFrame, true)
            binding.root
        }
        itemView
    }) {

        private val mineBinding: ChatItemChatMessageListTextMineBinding? =
            if (isMine) ChatItemChatMessageListTextMineBinding.bind(itemView) else null

        private val othersBinding: ChatItemChatMessageListTextOthersBinding? =
            if (!isMine) ChatItemChatMessageListTextOthersBinding.bind(itemView) else null

        
        private var highlightRunnable: Runnable? = null


        private val contentFrame: ViewGroup
            get() = if (isMine) mineBinding!!.contentFrame else othersBinding!!.contentFrame

        
        val bubble: ChatMessageContainerView
            get() = mineBinding?.contentContainer ?: othersBinding!!.contentContainer

        private val contentContainer: View
            get() = if (isMine) mineBinding!!.contentContainer else othersBinding!!.contentContainer

        private val clMessageTime: View
            get() = if (isMine) mineBinding!!.clMessageTime else othersBinding!!.clMessageTime

        private val textViewTime: TextView
            get() = if (isMine) mineBinding!!.textViewTime else othersBinding!!.textViewTime


        private val timeWrapper: View
            get() = if (isMine) mineBinding!!.llTimeWrapper else othersBinding!!.llTimeWrapper

        private val llNewMsgDivider: ViewGroup
            get() = if (isMine) mineBinding!!.llNewMsgDivider.root else othersBinding!!.llNewMsgDivider.root

        private val tvDayTime: TextView
            get() = if (isMine) mineBinding!!.tvDayTime else othersBinding!!.tvDayTime

        private val quoteZone: View
            get() = if (isMine) mineBinding!!.quoteZone else othersBinding!!.quoteZone

        private val quoteAuthor: TextView
            get() = if (isMine) mineBinding!!.quoteAuthor else othersBinding!!.quoteAuthor

        private val quoteText: TextView
            get() = if (isMine) mineBinding!!.quoteText else othersBinding!!.quoteText

        private val quoteThumbnail: ImageView
            get() = if (isMine) mineBinding!!.quoteThumbnail else othersBinding!!.quoteThumbnail

        private val quoteBar: View
            get() = if (isMine) mineBinding!!.quoteBar else othersBinding!!.quoteBar

        
        private val forwardInfoZone: View
            get() = if (isMine) mineBinding!!.forwardInfoZone.root else othersBinding!!.forwardInfoZone.root

        private val tvForwardFrom: TextView
            get() = if (isMine) mineBinding!!.forwardInfoZone.tvForwardFrom else othersBinding!!.forwardInfoZone.tvForwardFrom

        private val reactionsView: FlowLayout
            get() = if (isMine) mineBinding!!.reactionsView else othersBinding!!.reactionsView

        private val clTranslate: ConstraintLayout
            get() = if (isMine) mineBinding!!.clTranslate else othersBinding!!.clTranslate

        private val pbTranslate: ProgressBar
            get() = if (isMine) mineBinding!!.pbTranslate else othersBinding!!.pbTranslate

        private val tvTranslateContent: AppCompatTextView
            get() = if (isMine) mineBinding!!.tvTranslateContent else othersBinding!!.tvTranslateContent

        private val tvTranslateAttribution: AppCompatTextView
            get() = if (isMine) mineBinding!!.tvTranslateAttribution else othersBinding!!.tvTranslateAttribution

        private val tvTranslateError: AppCompatTextView
            get() = if (isMine) mineBinding!!.tvTranslateError else othersBinding!!.tvTranslateError

        private val translateViews: TranslateBlockBinder.Views
            get() = TranslateBlockBinder.Views(
                container = clTranslate,
                progress = pbTranslate,
                content = tvTranslateContent,
                attribution = tvTranslateAttribution,
                error = tvTranslateError,
            )

        private val clSpeechToText: ConstraintLayout
            get() = if (isMine) mineBinding!!.clSpeechToText else othersBinding!!.clSpeechToText

        private val pbSpeechToText: ProgressBar
            get() = if (isMine) mineBinding!!.pbSpeechToText else othersBinding!!.pbSpeechToText

        private val tvSpeechToTextContent: AppCompatTextView
            get() = if (isMine) mineBinding!!.tvSpeechToTextContent else othersBinding!!.tvSpeechToTextContent

        private val ivSpeech2textServerTipIcon: ImageView
            get() = if (isMine) mineBinding!!.ivSpeech2textServerTipIcon else othersBinding!!.ivSpeech2textServerTipIcon

        private val checkboxSelectForUnpin: DifftCheckBoxView
            get() = if (isMine) mineBinding!!.checkboxSelectForUnpin else othersBinding!!.checkboxSelectForUnpin

        
        private val tvVoiceSpeed: AppCompatTextView
            get() = if (isMine) mineBinding!!.tvVoiceSpeed else othersBinding!!.tvVoiceSpeed


        private fun resetViewDefaults() {
            val timeLayoutParams = timeWrapper.layoutParams as? LinearLayout.LayoutParams
            timeLayoutParams?.let {
                it.topMargin = (-26).dp
                timeWrapper.layoutParams = it
            }

            
            val textView = contentFrame.findViewById<TextView>(R.id.textView)
            textView?.setPaddingRelative(12.dp, 10.dp, 12.dp, 10.dp)

            reactionsView.setPaddingRelative(12.dp, 4.dp, 12.dp, 8.dp)

            
            tvVoiceSpeed.visibility = View.GONE
        }

        







        fun updateVoiceSpeedButton(visible: Boolean, speed: Float, onSpeedClick: (() -> Unit)?) {
            tvVoiceSpeed.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) {
                tvVoiceSpeed.text = formatSpeedText(speed)
                tvVoiceSpeed.setOnClickListener {
                    onSpeedClick?.invoke()
                }
            } else {
                tvVoiceSpeed.setOnClickListener(null)
            }
        }

        private fun formatSpeedText(speed: Float): String {
            return when (speed) {
                1.5f -> "1.5×"
                2.0f -> "2×"
                else -> "1×"
            }
        }

        



        private fun bindVoiceSpeedButton(message: TextChatMessage) {
            val currentPlayingMessage = AudioMessageManager.currentPlayingMessage
            
            
            val isThisMessagePlaying = currentPlayingMessage?.id == message.id && !AudioMessageManager.isPaused

            if (isThisMessagePlaying) {
                val currentSpeed = AudioMessageManager.playbackSpeed.value
                updateVoiceSpeedButton(true, currentSpeed) {
                    AudioMessageManager.cyclePlaybackSpeed()
                }
            }
            
        }

        





        private val containerWidth: Int
            get() = parentView.width

        




        private var boundMessage: ChatMessage? = null

        override fun bind(
            message: ChatMessage,
            callbacks: MessageCallbacks,
            highlightItemIds: ArrayList<Long>?,
            contactorCache: MessageContactsCacheUtil,
            shouldSaveToPhotos: Boolean
        ) {
            val cb = callbacks as? MessageCallbacks.MessageInteraction
                ?: throw IllegalArgumentException("Message ViewHolder requires MessageInteraction callbacks")

            resetViewDefaults()

            boundMessage = message
            if (message !is TextChatMessage) return

            
            bindVoiceSpeedButton(message)


            bindClickEvents(message, cb.onItemClick, cb.onItemLongClick)

            bindTimeViews(message)

            bindQuoteView(message, cb.onQuoteClicked, contactorCache)

            bindForwardView(message, contactorCache, shouldSaveToPhotos)

            bindReactionView(message, cb.onReactionClick, cb.onReactionLongClick, contactorCache)
            bindTranslateView(message, cb.onTranslateRetry)
            bindSpeechToTextView(message)
            (contentContainer as? ChatMessageContainerView)?.let {
                maskConfidentialBubble(it, listOf(quoteZone, forwardInfoZone, reactionsView), message.isConfidential())
            }

            bindCheckboxView(message, cb.onSelectPinnedMessage)


            if (isMine) {
                bindSendAndReadStatus(message.sendStatus, message.readStatus, message.readContactNumber)
            } else {
                bindAvatarAndName(message, cb.onAvatarClicked, cb.onAvatarLongClicked, contactorCache)

                bindHighlightEffect(message, highlightItemIds)
            }
        }


        private fun bindClickEvents(
            message: TextChatMessage,
            onItemClick: (rootView: View, message: ChatMessage) -> Unit,
            onItemLongClick: (rootView: View, message: ChatMessage) -> Unit
        ) {
            contentContainer.setOnLongClickListener {
                onItemLongClick(it.parent as ConstraintLayout, boundMessage ?: message)
                true
            }

            contentContainer.setOnClickListener {
                onItemClick(it, boundMessage ?: message)
            }
        }

        private fun bindTimeViews(message: TextChatMessage) {
            if (isMine) {
                setupMessageTimeStyle(
                    message = message,
                    timeContainer = clMessageTime,
                    timeTextView = textViewTime,
                    sendStatusImageView = mineBinding!!.ivSendStatus,
                    readNumberTextView = mineBinding.ivReadNumber
                )
            } else {
                setupMessageTimeStyle(
                    message = message,
                    timeContainer = clMessageTime,
                    timeTextView = textViewTime
                )
            }

            llNewMsgDivider.visibility = if (message.showNewMsgDivider) View.VISIBLE else View.GONE

            if (message.showDayTime) {
                tvDayTime.visibility = View.VISIBLE
                tvDayTime.text = TimeFormatter.getConversationDateHeaderString(
                    itemView.context, language, message.systemShowTimestamp
                )
            } else {
                tvDayTime.visibility = View.GONE
            }

            if (message.showTime) {
                if (isMine) {
                    textViewTime.visibility = View.VISIBLE
                    textViewTime.text = TimeFormatter.formatMessageTime(
                        language.language, message.systemShowTimestamp
                    )
                } else {
                    clMessageTime.visibility = View.VISIBLE
                    textViewTime.text = TimeFormatter.formatMessageTime(
                        language.language, message.systemShowTimestamp
                    )
                }
            } else {
                if (isMine) {
                    textViewTime.visibility = View.GONE
                } else {
                    clMessageTime.visibility = View.GONE
                }
            }
        }

        private fun bindQuoteView(
            message: TextChatMessage,
            onQuoteClicked: (quote: Quote) -> Unit,
            contactorCache: MessageContactsCacheUtil
        ) {
            if (message.quote != null) {
                quoteZone.visibility = View.VISIBLE
                quoteAuthor.text = if (message.quote?.author == globalServices.myId) {
                    itemView.context.getString(R.string.you)
                } else {
                    contactorCache.getContactor(message.quote!!.author)
                        ?.getDisplayNameForUI()
                        ?: message.quote!!.author.formatBase58Id()
                }
                
                quoteText.text = message.quote?.text?.normalizeNewlines()
                quoteZone.setOnClickListener { message.quote?.let { onQuoteClicked(it) } }
                styleQuoteBlock(quoteZone, quoteBar, quoteAuthor, quoteText, contentContainer.bubblePalette())

                
                bindQuoteThumbnail(quoteThumbnail, message.quote!!, forWhat)
            } else {
                quoteZone.visibility = View.GONE
                clearQuoteThumbnail()
            }
        }

        
        fun clearQuoteThumbnail() {
            clearQuoteThumbnail(quoteThumbnail)
        }

        private fun bindForwardView(message: TextChatMessage, contactorCache: MessageContactsCacheUtil, shouldSaveToPhotos: Boolean) {
            val forwardsSize = message.forwardContext?.forwards?.size ?: 0

            (contentFrame.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = 0
                contentFrame.layoutParams = it
            }

            
            bubble.capsWidth = contentBinder !== ImageContentBinder
            bubble.growsWithWindow = showsOnlyText(contentBinder, message)
            when {
                forwardsSize == 0 -> {
                    forwardInfoZone.visibility = View.GONE
                    contentFrame.visibility = View.VISIBLE
                    contentBinder.bind(contentFrame, message, contactorCache, shouldSaveToPhotos, containerWidth)
                }

                
                forwardsSize == 1 -> {
                    val forward = message.forwardContext?.forwards?.firstOrNull()
                    if (forward != null) {
                        forwardInfoZone.visibility = View.VISIBLE
                        val author = contactorCache.getContactor(forward.author)
                        tvForwardFrom.text = itemView.context.getString(
                            R.string.chat_forwarded_from,
                            author?.getDisplayNameForUI() ?: forward.author,
                        )
                        tvForwardFrom.setTextColor(contentContainer.bubblePalette().meta)

                        
                        val forwardMessage = generateMessageFromForward(forward, message.mode) as TextChatMessage
                        contentFrame.visibility = View.VISIBLE

                        
                        val attachment = forward.attachments?.firstOrNull()
                        if (attachment != null && (attachment.isImage() || attachment.isPlayableVideo())) {
                            (contentFrame.layoutParams as? LinearLayout.LayoutParams)?.let {
                                it.topMargin = 8.dp
                                contentFrame.layoutParams = it
                            }
                        }

                        
                        contentBinder.bind(contentFrame, forwardMessage, contactorCache, shouldSaveToPhotos, containerWidth)
                    }
                }

                else -> {
                    forwardInfoZone.visibility = View.GONE
                    contentFrame.visibility = View.VISIBLE
                    contentBinder.bind(contentFrame, message, contactorCache, shouldSaveToPhotos, containerWidth)
                }
            }
        }

        private fun bindReactionView(
            message: TextChatMessage,
            onReactionClick: (message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) -> Unit,
            onReactionLongClick: (message: ChatMessage, emoji: String) -> Unit,
            contactorCache: MessageContactsCacheUtil
        ) {
            initReactionView(itemView, reactionsView, message, onReactionClick, onReactionLongClick, contactorCache)
        }

        private fun bindTranslateView(message: TextChatMessage, onTranslateRetry: (TextChatMessage) -> Unit) {
            bindTranslateBlock(translateViews, message, onTranslateRetry)
        }

        
        fun bindTranslateOnly(message: ChatMessage, onTranslateRetry: (TextChatMessage) -> Unit) {
            if (message !is TextChatMessage) return
            boundMessage = message
            bindTranslateBlock(translateViews, message, onTranslateRetry)
        }

        
        fun bindWidthOnly(message: ChatMessage, contactorCache: MessageContactsCacheUtil, shouldSaveToPhotos: Boolean) {
            if (message !is TextChatMessage) return
            contentBinder.rebindForWidth(contentFrame, contentOf(message), contactorCache, shouldSaveToPhotos, containerWidth)
        }

        private fun bindSpeechToTextView(message: TextChatMessage) {
            initSpeechToTextView(
                message,
                clSpeechToText,
                pbSpeechToText,
                tvSpeechToTextContent,
                ivSpeech2textServerTipIcon
            )

            ivSpeech2textServerTipIcon.setOnClickListener {
                ComposeDialogManager.showMessageDialog(
                    context = itemView.context,
                    title = ApplicationHelper.instance.getString(R.string.chat_message_action_voice2text_tip_title),
                    message = ApplicationHelper.instance.getString(R.string.chat_message_action_voice2text_tip_info),
                    confirmText = ApplicationHelper.instance.getString(R.string.chat_message_action_voice2text_tip_ok),
                    showCancel = false
                )
            }
        }

        



        private fun bindSelectionCheckbox(
            message: TextChatMessage,
            onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?
        ) {
            checkboxSelectForUnpin.isChecked = message.selectedStatus
            checkboxSelectForUnpin.setOnCheckedChangeListener { _, b ->
                if (message.isConfidential()) {
                    checkboxSelectForUnpin.isChecked = message.selectedStatus
                    ToastUtil.show(itemView.context.getString(R.string.chat_confidential_can_not_select))
                } else if (b != message.selectedStatus) {
                    onSelectPinnedMessage?.invoke(message.id, b)
                }
            }
        }

        private fun bindCheckboxView(
            message: TextChatMessage,
            onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?
        ) {
            bindSelectionCheckbox(message, onSelectPinnedMessage)
            checkboxSelectForUnpin.visibility =
                if (message.editMode && message.attachment?.isAudioMessage() != true) View.VISIBLE
                else if (message.editMode && message.attachment?.isAudioMessage() == true) View.INVISIBLE
                else View.GONE
        }

        

        




        fun bindSelectionOnly(
            message: ChatMessage,
            onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?
        ) {
            if (message !is TextChatMessage) return

            bindSelectionCheckbox(message, onSelectPinnedMessage)
        }

        




        fun bindHighlightOnly(message: ChatMessage, highlightItemIds: ArrayList<Long>?) {
            if (message !is TextChatMessage) return
            if (isMine) return  

            
            clearHighlight()

            highlightItemIds?.let {
                if (it.contains(message.timeStamp)) {
                    itemView.setBackgroundColor(
                        ContextCompat.getColor(itemView.context, com.difft.android.base.R.color.bg2)
                    )
                    highlightRunnable = Runnable {
                        itemView.setBackgroundColor(0)
                        highlightItemIds.remove(message.timeStamp)
                    }
                    itemView.postDelayed(highlightRunnable, 1000)
                }
            }
        }

        


        fun clearHighlight() {
            highlightRunnable?.let {
                itemView.removeCallbacks(it)
            }
            highlightRunnable = null
        }


        private fun bindAvatarAndName(
            message: TextChatMessage,
            avatarClicked: (contactor: ContactorModel) -> Unit,
            avatarLongClicked: (contactor: ContactorModel) -> Unit,
            contactorCache: MessageContactsCacheUtil
        ) {
            val binding = othersBinding ?: return
            
            
            val senderColumn = binding.contentContainer.avatarColumn
            placeBubbleAfterAvatarColumn(binding.contentContainer, senderColumn)
            if (!senderColumn) {
                binding.imageviewAvatar.visibility = View.GONE
                binding.clName.visibility = View.GONE
                return
            }
            if (!message.showName) {
                binding.imageviewAvatar.visibility = View.INVISIBLE
                binding.clName.visibility = View.GONE
                return
            }
            binding.imageviewAvatar.visibility = View.VISIBLE
            binding.clName.visibility = View.VISIBLE
            val contactor = contactorCache.getContactor(message.authorId)
            if (contactor != null) {
                binding.imageviewAvatar.setAvatar(contactor, letterTextSizeDp = 12)
                binding.imageviewAvatar.setOnClickListener { avatarClicked(contactor) }
                binding.imageviewAvatar.setOnLongClickListener {
                    avatarLongClicked(contactor)
                    true
                }
                binding.textviewNickname.text = contactor.getDisplayNameForUI()
            } else {
                
                binding.imageviewAvatar.setAvatar(null, null, message.authorId.firstOrNull()?.toString(), message.authorId, letterTextSizeDp = 12)
                binding.imageviewAvatar.setOnClickListener(null)
                binding.imageviewAvatar.setOnLongClickListener(null)
                binding.textviewNickname.text = message.authorId
            }
        }

        private fun bindHighlightEffect(
            message: TextChatMessage,
            highlightItemIds: ArrayList<Long>?
        ) {
            
            clearHighlight()

            highlightItemIds?.let {
                if (it.contains(message.timeStamp)) {
                    itemView.setBackgroundColor(
                        ContextCompat.getColor(itemView.context, com.difft.android.base.R.color.bg2)
                    )
                    highlightRunnable = Runnable {
                        itemView.setBackgroundColor(0)
                        highlightItemIds.remove(message.timeStamp)
                    }
                    itemView.postDelayed(highlightRunnable, 1000)
                }
            }
        }

        
        @SuppressLint("SetTextI18n")
        private fun bindSendAndReadStatus(
            sendStatus: Int?,
            readStatus: Int,
            readContactNumber: Int
        ) {
            val ivSendStatus = mineBinding!!.ivSendStatus
            val ivReadNumber = mineBinding.ivReadNumber

            ivSendStatus.visibility = View.GONE
            ivReadNumber.visibility = View.GONE

            
            
            
            bindSendFailStatusRow(mineBinding.llSendFailStatus, sendStatus) {
                contentContainer.performClick()
            }

            when (sendStatus) {
                SendType.Sending.rawValue -> {
                    ivSendStatus.visibility = View.VISIBLE
                    ivSendStatus.setImageResource(R.drawable.chat_icon_message_sending_new)
                }

                SendType.Sent.rawValue -> {
                    if (readStatus == 1) {
                        ivSendStatus.visibility = View.VISIBLE
                        ivSendStatus.setImageResource(R.drawable.chat_icon_message_read)
                    } else {
                        if (readContactNumber == 0) {
                            ivSendStatus.visibility = View.VISIBLE
                            ivSendStatus.setImageResource(R.drawable.chat_icon_message_sent_success)
                        } else {
                            ivReadNumber.visibility = View.VISIBLE
                            ivReadNumber.text = readContactNumber.toString()
                        }
                    }
                }

                
                SendType.SentFailed.rawValue -> Unit

                else -> {
                    ivSendStatus.visibility = View.GONE
                    ivReadNumber.visibility = View.GONE
                }
            }
        }
    }

    class Notify(
        private val parentView: ViewGroup,
        contentLayoutRes: Int,
        private val contentBinder: ContentBinder
    ) : ChatMessageViewHolder(run {
        val context = parentView.context
        val layoutInflater = LayoutInflater.from(context)
        val binding = ChatItemChatMessageListNotifyBinding.inflate(layoutInflater, parentView, false)
        layoutInflater.inflate(contentLayoutRes, binding.contentFrame, true)
        binding.root
    }) {
        private val binding: ChatItemChatMessageListNotifyBinding =
            ChatItemChatMessageListNotifyBinding.bind(itemView)

        private val containerWidth: Int
            get() = parentView.width

        override fun bind(
            message: ChatMessage,
            callbacks: MessageCallbacks,
            highlightItemIds: ArrayList<Long>?,
            contactorCache: MessageContactsCacheUtil,
            shouldSaveToPhotos: Boolean
        ) {
            val cb = callbacks as? MessageCallbacks.NotifyInteraction
                ?: throw IllegalArgumentException("Notify ViewHolder requires NotifyInteraction callbacks")

            if (message.showNewMsgDivider) {
                binding.llNewMsgDivider.root.visibility = View.VISIBLE
            } else {
                binding.llNewMsgDivider.root.visibility = View.GONE
            }

            if (message.showDayTime) {
                binding.tvDayTime.visibility = View.VISIBLE
                binding.tvDayTime.text = TimeFormatter.getConversationDateHeaderString(
                    binding.root.context, language, message.systemShowTimestamp
                )
            } else {
                binding.tvDayTime.visibility = View.GONE
            }

            
            contentBinder.bind(binding.contentFrame, message, contactorCache, shouldSaveToPhotos, containerWidth)

            if (message is EncryptionHeaderChatMessage) {
                binding.root.minimumHeight = 48.dp 
                binding.root.isClickable = true
                binding.root.setOnClickListener { cb.onE2eeHeaderClick?.invoke() }
            } else {
                
                
                
                
                
                
                binding.root.setOnClickListener(null)
                binding.root.isClickable = false
            }
        }
    }


    
    @SuppressLint("SetTextI18n")
    fun initReactionView(
        root: View,
        reactionsView: FlowLayout,
        message: TextChatMessage,
        onReactionClick: (message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) -> Unit,
        onReactionLongClick: (message: ChatMessage, emoji: String) -> Unit,
        contactorCache: MessageContactsCacheUtil
    ) {
        if (message.reactions != null && !message.reactions.isNullOrEmpty()) {
            reactionsView.visibility = View.VISIBLE
            reactionsView.removeAllViews()
            val palette = reactionsView.bubblePalette()

            val emojis = message.reactions?.groupBy { it.emoji }

            emojis?.forEach { reaction ->
                val reactionItem = LayoutInflater.from(reactionsView.context)
                    .inflate(R.layout.chat_item_reaction, reactionsView, false)
                reactionItem.findViewById<AppCompatTextView>(R.id.tv_emoji).text = reaction.key
                val reactions = reaction.value.distinctBy { it.uid }
                if (reactions.size == 1) {
                    val id = reactions[0].uid
                    val contact = contactorCache.getContactor(id)
                    var text: String = contact?.getDisplayNameForUI() ?: id.formatBase58Id()
                    if (text.length > 10) {
                        text = text.substring(0, 10) + "..."
                    }
                    reactionItem.findViewById<AppCompatTextView>(R.id.tv_count).text = text
                } else {
                    reactionItem.findViewById<AppCompatTextView>(R.id.tv_count).text = reactions.size.toString()
                }
                reactionItem.isSelected = reactions.find { it.uid == myID } != null
                styleReactionChip(reactionItem, palette)

                reactionItem.setOnClickListener {
                    onReactionClick(
                        message, reaction.key, reactionItem.isSelected,
                        reactions.find { it.uid == myID }?.originTimestamp ?: 0L
                    )
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

    



    private fun styleReactionChip(chip: View, palette: BubblePalette) {
        val density = chip.resources.displayMetrics.density
        chip.background = GradientDrawable().apply {
            cornerRadius = REACTION_CHIP_RADIUS_DP * density
            setColor(palette.blockFill)
            if (chip.isSelected) setStroke(density.roundToInt().coerceAtLeast(1), palette.text)
        }
        chip.findViewById<TextView>(R.id.tv_emoji).setTextColor(palette.text)
        chip.findViewById<TextView>(R.id.tv_count).setTextColor(palette.text)
    }

    




    internal fun bindTranslateBlock(
        views: TranslateBlockBinder.Views,
        message: TextChatMessage,
        onTranslateRetry: (TextChatMessage) -> Unit,
    ) {
        TranslateBlockBinder.bind(views, message, onTranslateRetry, ::showContent)
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
                    if (message.attachment?.flags == 1) {
                        showContent(tvSpeechToTextContent, it.speechToTextContent ?: "", message)
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

    
    private fun showContent(
        textView: AppCompatTextView,
        content: String,
        sourceMessage: TextChatMessage,
    ) {
        textView.text = content
        textView.setOnLongClickListener {
            Util.copyToClipboard(textView.context, content)
            val conv = sourceMessage.forWhat
            
            val myId = globalServices.myId
            if (conv != null && sourceMessage.authorId.isNotEmpty() &&
                NoticeAggregator.copyCarriesForeignContent(listOf(sourceMessage), myId)
            ) {
                
                ApplicationDependencies.getActivityNoticeDispatcher()
                    .dispatchCopyNotice(
                        sourceConversation = conv,
                        sourceAuthorIds = listOf(sourceMessage.authorId),
                        myId = myId,
                        messageCount = 1,
                        combinedForwardMode = CombinedForwardMode.UNKNOWN,
                    )
            }
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
        val thirdColor = timeContainer.bubblePalette().meta

        if (shouldShowShadow) {
            timeContainer.setBackgroundResource(R.drawable.chat_message_item_time_bg)
            timeTextView.setTextColor(whiteColor)

            sendStatusImageView?.imageTintList = ContextCompat.getColorStateList(
                timeContainer.context,
                com.difft.android.base.R.color.t_white
            )

            readNumberTextView?.apply {
                background = ResUtils.getDrawable(R.drawable.chat_read_number_bg_white)
                setTextColor(whiteColor)
            }

            timeContainer.setRightMargin(8.dp)
        } else {
            timeContainer.background = null
            timeTextView.setTextColor(thirdColor)

            sendStatusImageView?.imageTintList = ColorStateList.valueOf(thirdColor)

            readNumberTextView?.apply {
                background = ResUtils.getDrawable(R.drawable.chat_read_number_bg)
                setTextColor(thirdColor)
            }

            timeContainer.setRightMargin(0)
        }
    }

    private fun shouldShowMessageTimeShadow(message: TextChatMessage): Boolean {
        
        if (message.isConfidential()) return true

        if (!message.reactions.isNullOrEmpty()) return false

        
        if (message.isAttachmentMessage() && message.attachment?.let { it.isImage() || it.isPlayableVideo() } == true) {
            return true
        }
        if (message.forwardContext?.forwards?.size == 1) {
            val forward = message.forwardContext?.forwards?.firstOrNull()
            if (forward?.attachments?.any { it.isImage() || it.isPlayableVideo() } == true) {
                return true
            }
        }

        return false
    }
}








private val quoteThumbnailTokenSeq = AtomicLong()






private val quoteThumbnailPaths = LruCache<String, String>(QUOTE_THUMBNAIL_PATHS)
private const val QUOTE_THUMBNAIL_PATHS = 200








internal fun bindQuoteThumbnail(imageView: ImageView, quote: Quote, forWhat: For?) {
    val qa = quote.attachments?.firstOrNull()
    if (qa == null) {
        clearQuoteThumbnail(imageView)
        return
    }

    val isVoice = qa.flags == 1 || MediaUtil.isAudioType(qa.contentType)
    
    val token = quoteThumbnailTokenSeq.incrementAndGet()
    imageView.setTag(R.id.quote_thumbnail_job, token)
    when {
        isVoice -> {
            imageView.visibility = View.VISIBLE
            QuoteThumbnailBinder.setTypeIcon(imageView, R.drawable.chat_ic_quote_mic, imageView.bubblePalette().icon)
        }
        
        MediaUtil.isImageType(qa.contentType) || isWhitelistedVideoType(qa.contentType) -> {
            val thumbBytes = qa.thumbnail?.thumbnail?.takeIf { it.isNotEmpty() }
            if (thumbBytes != null) {
                
                imageView.visibility = View.VISIBLE
                QuoteThumbnailBinder.loadRoundedThumbnail(imageView, thumbBytes, rounded = false)
            } else {
                
                
                
                val roomType = forWhat?.typeValue ?: 0
                val known = forWhat?.id?.let { quoteThumbnailPaths.get(quoteThumbnailKey(it, roomType, quote.id)) }
                if (known != null) {
                    imageView.visibility = View.VISIBLE
                    QuoteThumbnailBinder.loadRoundedThumbnail(imageView, EncryptedAttachmentAccess.imageGlideModel(known), rounded = false)
                } else {
                    clearQuoteThumbnail(imageView)
                    imageView.setTag(R.id.quote_thumbnail_job, token)
                }
                resolveOriginalThumbnailAsync(imageView, quote.id, forWhat?.id, roomType, token, shown = known)
            }
        }
        else -> {
            
            imageView.visibility = View.VISIBLE
            QuoteThumbnailBinder.setTypeIcon(imageView, fileTypeIcon(qa.fileName), imageView.bubblePalette().icon)
        }
    }
}










private fun resolveOriginalThumbnailAsync(
    imageView: ImageView,
    originalTimestamp: Long,
    roomId: String?,
    roomType: Int,
    token: Long,
    shown: String?,
) {
    if (roomId.isNullOrEmpty()) return
    
    
    
    val owner = imageView.getLifecycleOwner() ?: return
    owner.lifecycleScope.launch {
        val path = withContext(Dispatchers.IO) {
            findOriginalAttachmentPath(originalTimestamp, roomId, roomType)
        }
        val key = quoteThumbnailKey(roomId, roomType, originalTimestamp)
        if (path == null) quoteThumbnailPaths.remove(key) else quoteThumbnailPaths.put(key, path)
        
        if (path == shown || imageView.getTag(R.id.quote_thumbnail_job) != token || !imageView.isHostActivityAlive()) return@launch
        if (path == null) {
            clearQuoteThumbnail(imageView)
        } else {
            imageView.visibility = View.VISIBLE
            
            QuoteThumbnailBinder.loadRoundedThumbnail(imageView, EncryptedAttachmentAccess.imageGlideModel(path), rounded = false)
        }
    }
}

private fun quoteThumbnailKey(roomId: String, roomType: Int, timestamp: Long) = "$roomType:$roomId:$timestamp"












internal fun findOriginalAttachmentPath(timestamp: Long, roomId: String, roomType: Int): String? = runCatching {
    val original = wcdb.message.getFirstObject(
        DBMessageModel.roomId.eq(roomId)
            .and(DBMessageModel.roomType.eq(roomType))
            .and(DBMessageModel.timeStamp.eq(timestamp))
    ) ?: return null

    
    val forward = original.forwardContext()?.forwards?.takeIf { it.size == 1 }?.firstOrNull()
    if (forward != null) {
        val att = forward.attachments?.firstOrNull() ?: return null
        if (!MediaUtil.isImageType(att.contentType) && !att.isPlayableVideo()) return null
        att.fileName ?: return null
        val path = AttachmentPathResolver.materializedFileFor(att, original.id)
        
        
        return path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
    }

    
    val att = original.attachment()?.takeIf { MediaUtil.isImageType(it.contentType) || it.isPlayableVideo() } ?: return null
    att.fileName ?: return null
    val path = AttachmentPathResolver.materializedFileFor(att, original.id)
    path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
}.onFailure {
    L.w { "[QuoteThumb] findOriginalAttachmentPath failed ts=$timestamp: ${it.stackTraceToString()}" }
}.getOrNull()


internal fun clearQuoteThumbnail(imageView: ImageView) {
    imageView.setTag(R.id.quote_thumbnail_job, null)
    if (imageView.isHostActivityAlive()) Glide.with(imageView).clear(imageView)
    imageView.setImageDrawable(null)
    imageView.visibility = View.GONE
}


private fun showsOnlyText(binder: ContentBinder, message: TextChatMessage): Boolean = when (binder) {
    TextContentBinder -> true
    AttachContentBinder -> contentOf(message).attachment?.isLongText() == true
    else -> false
}


private fun contentOf(message: TextChatMessage): TextChatMessage {
    val forwards = message.forwardContext?.forwards
    return if (forwards?.size == 1) generateMessageFromForward(forwards.first(), message.mode) as TextChatMessage else message
}
