package com.difft.android.chat.ui

import android.annotation.SuppressLint
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
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.dp
import com.difft.android.base.utils.windowWidthPx
import com.difft.android.base.utils.getLifecycleOwner
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.base.widget.setRightMargin
import com.difft.android.chat.R
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.common.SendType
import com.difft.android.chat.databinding.ChatItemChatMessageListNotifyBinding
import com.difft.android.chat.databinding.ChatItemChatMessageListTextMineBinding
import com.difft.android.chat.databinding.ChatItemChatMessageListTextOthersBinding
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.NoticeAggregator
import com.difft.android.chat.message.TextChatMessage
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
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
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

/**
 * 消息交互回调封装
 *
 * 使用密封类为不同类型的消息提供独立的回调集合
 */
sealed class MessageCallbacks {
    /**
     * 普通消息的交互回调
     *
     * 包含点击、长按、头像、引用、Reaction 等所有交互事件
     */
    data class MessageInteraction(
        val onItemClick: (rootView: View, message: ChatMessage) -> Unit,
        val onItemLongClick: (rootView: View, message: ChatMessage) -> Unit,
        val onAvatarClicked: (contactor: ContactorModel) -> Unit,
        val onAvatarLongClicked: (contactor: ContactorModel) -> Unit,
        val onQuoteClicked: (quote: Quote) -> Unit,
        val onReactionClick: (message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) -> Unit,
        val onReactionLongClick: (message: ChatMessage, emoji: String) -> Unit,
        val onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?
    ) : MessageCallbacks()

    /**
     * 通知消息的交互回调
     *
     * 预留用于扩展通知消息特有的交互（如接受好友请求等）
     */
    data class NotifyInteraction(
        val placeholder: Unit = Unit
    ) : MessageCallbacks()
}

/**
 * 聊天消息 ViewHolder 基类
 *
 * 提供两种 ViewHolder 实现：
 * - Message: 普通消息（文本、图片、视频、语音等），使用 isMine 参数区分自己/他人
 * - Notify: 通知消息
 */
abstract class ChatMessageViewHolder(itemView: View) : ViewHolder(itemView) {

    val myID: String by lazy {
        globalServices.myId
    }

    val language by lazy {
        LanguageUtils.getLanguage(application)
    }

    /**
     * 绑定消息数据到 ViewHolder
     *
     * @param message 消息数据
     * @param callbacks 交互回调（Message 使用 MessageInteraction，Notify 使用 NotifyInteraction）
     * @param highlightItemIds 需要高亮的消息ID列表
     * @param contactorCache 联系人缓存实例（页面级）
     * @param shouldSaveToPhotos 是否应该自动保存到相册（基于会话级/全局设置）
     */
    abstract fun bind(
        message: ChatMessage,
        callbacks: MessageCallbacks,
        highlightItemIds: ArrayList<Long>? = null,
        contactorCache: MessageContactsCacheUtil,
        shouldSaveToPhotos: Boolean = false
    )

    /**
     * 普通消息 ViewHolder
     *
     * @param isMine 是否为自己发送的消息，用于区分布局和显示逻辑
     * @param contentLayoutRes 消息内容布局资源 ID
     * @param contentBinder 消息内容绑定器
     * @param forWhat 消息用途标识
     */
    class Message(
        private val parentView: ViewGroup,
        private val isMine: Boolean,
        contentLayoutRes: Int,
        private val contentBinder: ContentBinder,
        private val forWhat: For?
    ) : ChatMessageViewHolder(run {
        val context = parentView.context
        val layoutInflater = LayoutInflater.from(context)

        // 根据 isMine 使用不同的布局
        val itemView = if (isMine) {
            val binding = ChatItemChatMessageListTextMineBinding.inflate(layoutInflater, parentView, false)
            layoutInflater.inflate(contentLayoutRes, binding.contentFrame, true)
            binding.root
        } else {
            val binding = ChatItemChatMessageListTextOthersBinding.inflate(layoutInflater, parentView, false)
            layoutInflater.inflate(contentLayoutRes, binding.contentFrame, true)
            binding.root
        }
        itemView
    }) {

        // 根据 isMine 绑定对应的 ViewBinding
        private val mineBinding: ChatItemChatMessageListTextMineBinding? =
            if (isMine) ChatItemChatMessageListTextMineBinding.bind(itemView) else null

        private val othersBinding: ChatItemChatMessageListTextOthersBinding? =
            if (!isMine) ChatItemChatMessageListTextOthersBinding.bind(itemView) else null

        // Runnable for highlight effect cleanup (to prevent memory leak on ViewHolder recycle)
        private var highlightRunnable: Runnable? = null

        // ========== 通用 View 引用（兼容 Mine 和 Others） ==========

        private val contentFrame: ViewGroup
            get() = if (isMine) mineBinding!!.contentFrame else othersBinding!!.contentFrame

        private val contentContainer: View
            get() = if (isMine) mineBinding!!.contentContainer else othersBinding!!.contentContainer

        private val clMessageTime: View
            get() = if (isMine) mineBinding!!.clMessageTime else othersBinding!!.clMessageTime

        private val textViewTime: TextView
            get() = if (isMine) mineBinding!!.textViewTime else othersBinding!!.textViewTime

        private val confidentialIcon: View
            get() = if (isMine) mineBinding!!.ivConfidentialIcon else othersBinding!!.ivConfidentialIcon

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

        // 单条转发消息的信息头（时间+来源）
        private val forwardInfoZone: View
            get() = if (isMine) mineBinding!!.forwardInfoZone.root else othersBinding!!.forwardInfoZone.root

        private val tvForwardTime: TextView
            get() = if (isMine) mineBinding!!.forwardInfoZone.tvForwardTime else othersBinding!!.forwardInfoZone.tvForwardTime

        private val tvForwardAuthor: TextView
            get() = if (isMine) mineBinding!!.forwardInfoZone.tvForwardAuthor else othersBinding!!.forwardInfoZone.tvForwardAuthor

        private val reactionsView: FlowLayout
            get() = if (isMine) mineBinding!!.reactionsView else othersBinding!!.reactionsView

        private val clTranslate: ConstraintLayout
            get() = if (isMine) mineBinding!!.clTranslate else othersBinding!!.clTranslate

        private val pbTranslate: ProgressBar
            get() = if (isMine) mineBinding!!.pbTranslate else othersBinding!!.pbTranslate

        private val tvTranslateContent: AppCompatTextView
            get() = if (isMine) mineBinding!!.tvTranslateContent else othersBinding!!.tvTranslateContent

        private val clSpeechToText: ConstraintLayout
            get() = if (isMine) mineBinding!!.clSpeechToText else othersBinding!!.clSpeechToText

        private val pbSpeechToText: ProgressBar
            get() = if (isMine) mineBinding!!.pbSpeechToText else othersBinding!!.pbSpeechToText

        private val tvSpeechToTextContent: AppCompatTextView
            get() = if (isMine) mineBinding!!.tvSpeechToTextContent else othersBinding!!.tvSpeechToTextContent

        private val ivSpeech2textServerTipIcon: ImageView
            get() = if (isMine) mineBinding!!.ivSpeech2textServerTipIcon else othersBinding!!.ivSpeech2textServerTipIcon

        private val checkboxSelectForUnpin: androidx.appcompat.widget.AppCompatCheckBox
            get() = if (isMine) mineBinding!!.checkboxSelectForUnpin else othersBinding!!.checkboxSelectForUnpin

        // Voice speed button
        private val tvVoiceSpeed: AppCompatTextView
            get() = if (isMine) mineBinding!!.tvVoiceSpeed else othersBinding!!.tvVoiceSpeed

        // ========== 核心 bind 方法 ==========

        private fun resetViewDefaults() {
            val timeLayoutParams = timeWrapper.layoutParams as? LinearLayout.LayoutParams
            timeLayoutParams?.let {
                it.topMargin = (-26).dp
                timeWrapper.layoutParams = it
            }

            val textView = contentFrame.findViewById<TextView>(R.id.textView)
            textView?.setPaddingRelative(8.dp, 8.dp, 8.dp, 8.dp)

            reactionsView.setPaddingRelative(8.dp, 4.dp, 8.dp, 8.dp)

            // Reset voice speed button to hidden by default
            tvVoiceSpeed.visibility = View.GONE
        }

        /**
         * Update voice speed button visibility and text
         * Called from Fragment when audio playback state changes
         *
         * @param visible Whether the button should be visible
         * @param speed The current playback speed (1.0f, 1.5f, or 2.0f)
         * @param onSpeedClick Callback when button is clicked
         */
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

        /**
         * Bind voice speed button based on current playback state
         * Called during view binding to restore speed button visibility when recycled views are rebound
         */
        private fun bindVoiceSpeedButton(message: TextChatMessage) {
            val currentPlayingMessage = AudioMessageManager.currentPlayingMessage
            // Show speed button only if this message is currently playing (not paused)
            // When paused, hide the button to avoid state inconsistency
            val isThisMessagePlaying = currentPlayingMessage?.id == message.id && !AudioMessageManager.isPaused

            if (isThisMessagePlaying) {
                val currentSpeed = AudioMessageManager.playbackSpeed.value
                updateVoiceSpeedButton(true, currentSpeed) {
                    AudioMessageManager.cyclePlaybackSpeed()
                }
            }
            // If not playing or paused, the button is already hidden by resetViewDefaults
        }

        /**
         * Get the container width from parent RecyclerView.
         * This is used for precise image/video sizing in dual-pane mode.
         * Note: We use parentView (stored from constructor) instead of itemView.parent
         * because itemView might not be attached to RecyclerView yet during bind().
         */
        private val containerWidth: Int
            get() = parentView.width

        override fun bind(
            message: ChatMessage,
            callbacks: MessageCallbacks,
            highlightItemIds: ArrayList<Long>?,
            contactorCache: MessageContactsCacheUtil,
            shouldSaveToPhotos: Boolean
        ) {
            // 强制类型检查
            val cb = callbacks as? MessageCallbacks.MessageInteraction
                ?: throw IllegalArgumentException("Message ViewHolder requires MessageInteraction callbacks")

            resetViewDefaults()

            if (message !is TextChatMessage) return

            // Set container width for precise layout calculation in dual-pane mode
            (contentContainer as? ChatMessageContainerView)?.containerWidth = containerWidth

            // Check if this message is currently playing audio and show speed button
            bindVoiceSpeedButton(message)

            // ========== 公共逻辑（80%） ==========

            // 点击事件
            bindClickEvents(message, cb.onItemClick, cb.onItemLongClick)

            // 时间相关
            bindTimeViews(message)

            // Quote 引用
            bindQuoteView(message, cb.onQuoteClicked, contactorCache)

            // Forward 转发
            bindForwardView(message, contactorCache, shouldSaveToPhotos)

            // Reaction、Translate、SpeechToText
            bindReactionView(message, cb.onReactionClick, cb.onReactionLongClick, contactorCache)
            bindTranslateView(message)
            bindSpeechToTextView(message)

            // Checkbox 选择
            bindCheckboxView(message, cb.onSelectPinnedMessage)

            // ========== 差异逻辑（20%） ==========

            if (isMine) {
                // Mine 特有：发送和已读状态
                bindSendAndReadStatus(message.sendStatus, message.readStatus, message.readContactNumber)
            } else {
                // Others 特有：头像和昵称
                bindAvatarAndName(message, cb.onAvatarClicked, cb.onAvatarLongClicked, contactorCache)

                // Others 特有：消息高亮
                bindHighlightEffect(message, highlightItemIds)
            }
        }

        // ========== 公共逻辑方法（80%） ==========

        private fun bindClickEvents(
            message: TextChatMessage,
            onItemClick: (rootView: View, message: ChatMessage) -> Unit,
            onItemLongClick: (rootView: View, message: ChatMessage) -> Unit
        ) {
            contentContainer.setOnLongClickListener {
                onItemLongClick(it.parent as ConstraintLayout, message)
                true
            }

            contentContainer.setOnClickListener {
                onItemClick(it, message)
            }
        }

        private fun bindTimeViews(message: TextChatMessage) {
            // 时间样式
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

            // 新消息分隔线
            llNewMsgDivider.visibility = if (message.showNewMsgDivider) View.VISIBLE else View.GONE

            // 日期分隔
            if (message.showDayTime) {
                tvDayTime.visibility = View.VISIBLE
                tvDayTime.text = TimeFormatter.getConversationDateHeaderString(
                    itemView.context, language, message.systemShowTimestamp
                )
            } else {
                tvDayTime.visibility = View.GONE
            }

            // 时间戳
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

            // 机密消息图标
            confidentialIcon.visibility = if (message.isConfidential()) View.VISIBLE else View.GONE
        }

        private fun bindQuoteView(
            message: TextChatMessage,
            onQuoteClicked: (quote: Quote) -> Unit,
            contactorCache: MessageContactsCacheUtil
        ) {
            if (message.quote != null) {
                quoteZone.visibility = View.VISIBLE
                // 如果引用的是自己的消息，显示 "你"；否则从缓存获取作者名称
                quoteAuthor.text = if (message.quote?.author == globalServices.myId) {
                    itemView.context.getString(R.string.you)
                } else {
                    // 从缓存中获取被引用消息作者的名称
                    contactorCache.getContactor(message.quote!!.author)
                        ?.getDisplayNameForUI()
                        ?: message.quote!!.author.formatBase58Id()
                }
                // Quote.text bypasses the normal display path, so normalize it here.
                quoteText.text = message.quote?.text?.normalizeNewlines()
                quoteZone.setOnClickListener { message.quote?.let { onQuoteClicked(it) } }

                // Cap the wrap_content text column so a long caption ellipsizes instead of pushing
                // the trailing thumbnail past the bubble's max width. Reserve the thumbnail width
                // when an attachment preview shows (mirrors iOS measureSize).
                val bubbleMaxWidth = (containerWidth.takeIf { it > 0 } ?: quoteText.windowWidthPx()) - 70.dp
                val hasAttachment = message.quote?.attachments?.isNotEmpty() == true
                // chrome = stripe + margins (+48 for thumbnail and its margin when shown)
                val textMaxWidth = (bubbleMaxWidth - if (hasAttachment) 75.dp else 27.dp).coerceAtLeast(40.dp)
                quoteAuthor.maxWidth = textMaxWidth
                quoteText.maxWidth = textMaxWidth

                bindQuoteThumbnail(quoteThumbnail, message.quote!!, forWhat)
            } else {
                quoteZone.visibility = View.GONE
                clearQuoteThumbnail()
            }
        }

        /** Recycle entry point: clears the thumbnail ImageView (delegates to the top-level fn). */
        fun clearQuoteThumbnail() {
            clearQuoteThumbnail(quoteThumbnail)
        }

        private fun bindForwardView(message: TextChatMessage, contactorCache: MessageContactsCacheUtil, shouldSaveToPhotos: Boolean) {
            val forwardsSize = message.forwardContext?.forwards?.size ?: 0

            // 重置 contentFrame 的 margin
            (contentFrame.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = 0
                contentFrame.layoutParams = it
            }

            when {
                // 无转发：普通消息
                forwardsSize == 0 -> {
                    forwardInfoZone.visibility = View.GONE
                    contentFrame.visibility = View.VISIBLE
                    contentBinder.bind(contentFrame, message, contactorCache, shouldSaveToPhotos, containerWidth)
                }

                // 单条转发：显示转发信息头 + 复用 contentFrame
                forwardsSize == 1 -> {
                    val forward = message.forwardContext?.forwards?.firstOrNull()
                    if (forward != null) {
                        // 显示转发信息头（直接绑定时间和来源）
                        forwardInfoZone.visibility = View.VISIBLE
                        tvForwardTime.text = util.TimeUtils.millis2String(forward.serverTimestampForUI, "yyyy/MM/dd HH:mm")
                        val author = contactorCache.getContactor(forward.author)
                        tvForwardAuthor.text = author?.getDisplayNameForUI() ?: forward.author

                        // 将 forward 转换为 TextChatMessage，复用 contentFrame（传递原消息的 mode）
                        val forwardMessage = generateMessageFromForward(forward, message.mode) as TextChatMessage
                        contentFrame.visibility = View.VISIBLE

                        // 图片/视频内容布局没有 padding，需要动态添加 margin
                        val attachment = forward.attachments?.firstOrNull()
                        if (attachment != null && (attachment.isImage() || attachment.isVideo())) {
                            (contentFrame.layoutParams as? LinearLayout.LayoutParams)?.let {
                                it.topMargin = 8.dp
                                contentFrame.layoutParams = it
                            }
                        }

                        // Forward message uses parent message's conversation setting
                        contentBinder.bind(contentFrame, forwardMessage, contactorCache, shouldSaveToPhotos, containerWidth)
                    }
                }

                // 合并转发：由 MultiForwardContentBinder 处理
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

        private fun bindTranslateView(message: TextChatMessage) {
            initTranslateView(message, clTranslate, pbTranslate, tvTranslateContent)
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

        private fun bindCheckboxView(
            message: TextChatMessage,
            onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?
        ) {
            checkboxSelectForUnpin.setOnCheckedChangeListener(null)
            checkboxSelectForUnpin.isChecked = message.selectedStatus
            checkboxSelectForUnpin.setOnCheckedChangeListener { _, b ->
                if (message.isConfidential()) {
                    checkboxSelectForUnpin.isChecked = message.selectedStatus
                    ToastUtil.show(itemView.context.getString(R.string.chat_confidential_can_not_select))
                } else {
                    if (b != message.selectedStatus) {
                        onSelectPinnedMessage?.invoke(message.id, b)
                    }
                }
            }
            checkboxSelectForUnpin.visibility =
                if (message.editMode && message.attachment?.isAudioMessage() != true) View.VISIBLE
                else if (message.editMode && message.attachment?.isAudioMessage() == true) View.INVISIBLE
                else View.GONE
        }

        // ========== Payload partial refresh methods ==========

        /**
         * Update selection UI only, without refreshing other content
         *
         * Used for Payload partial refresh, only updates checkbox state
         */
        fun bindSelectionOnly(
            message: ChatMessage,
            onSelectPinnedMessage: ((messageId: String, selected: Boolean) -> Unit)?
        ) {
            if (message !is TextChatMessage) return

            // Clear listener first to avoid triggering callback when setting isChecked
            checkboxSelectForUnpin.setOnCheckedChangeListener(null)
            checkboxSelectForUnpin.isChecked = message.selectedStatus
            // Re-set listener to ensure user clicks can be responded
            checkboxSelectForUnpin.setOnCheckedChangeListener { _, b ->
                if (message.isConfidential()) {
                    checkboxSelectForUnpin.isChecked = message.selectedStatus
                    ToastUtil.show(itemView.context.getString(R.string.chat_confidential_can_not_select))
                } else {
                    if (b != message.selectedStatus) {
                        onSelectPinnedMessage?.invoke(message.id, b)
                    }
                }
            }
        }

        /**
         * Update highlight effect only, without refreshing other content
         *
         * Used for Payload partial refresh, only updates background highlight
         */
        fun bindHighlightOnly(message: ChatMessage, highlightItemIds: ArrayList<Long>?) {
            if (message !is TextChatMessage) return
            if (isMine) return  // Highlight effect only applies to others' messages

            // Clear any pending highlight runnable to prevent memory leak
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

        /**
         * Clear pending highlight runnable to prevent memory leak when ViewHolder is recycled
         */
        fun clearHighlight() {
            highlightRunnable?.let {
                itemView.removeCallbacks(it)
            }
            highlightRunnable = null
        }

        // ========== 差异逻辑方法（20%） ==========

        private fun bindAvatarAndName(
            message: TextChatMessage,
            avatarClicked: (contactor: ContactorModel) -> Unit,
            avatarLongClicked: (contactor: ContactorModel) -> Unit,
            contactorCache: MessageContactsCacheUtil
        ) {
            if (message.showName) {
                othersBinding?.clName?.visibility = View.VISIBLE
                // 从缓存中获取联系人信息
                val contactor = contactorCache.getContactor(message.authorId)
                if (contactor != null) {
                    othersBinding?.imageviewAvatar?.setAvatar(contactor, letterTextSizeDp = 16)
                    othersBinding?.imageviewAvatar?.setOnClickListener { avatarClicked(contactor) }
                    othersBinding?.imageviewAvatar?.setOnLongClickListener {
                        avatarLongClicked(contactor)
                        true
                    }
                    // 使用从缓存获取的联系人显示名称
                    othersBinding?.textviewNickname?.text = contactor.getDisplayNameForUI()
                } else {
                    // 缓存miss时，使用 authorId 作为后备显示
                    othersBinding?.textviewNickname?.text = message.authorId
                }
            } else {
                othersBinding?.clName?.visibility = View.GONE
            }
        }

        private fun bindHighlightEffect(
            message: TextChatMessage,
            highlightItemIds: ArrayList<Long>?
        ) {
            // Clear any pending highlight runnable to prevent memory leak
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

        // Numeric-only display (read-recipient count); no English text to translate.
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

            // Failed state renders OUTSIDE the bubble (status row below it); the in-bubble
            // indicators only ever express sending / sent / read. Called unconditionally so every
            // rebind resets the row.
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

                // Rendered by the status row above.
                SendType.SentFailed.rawValue -> Unit

                else -> {
                    ivSendStatus.visibility = View.GONE
                    ivReadNumber.visibility = View.GONE
                }
            }
        }
    }

    /**
     * 通知消息 ViewHolder（保持不变）
     */
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
            // Notify 使用 NotifyInteraction（目前为空，未来可扩展）
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

            // 使用 ContentBinder 绑定内容（Notify消息不需要saveToPhotos，使用默认值false）
            contentBinder.bind(binding.contentFrame, message, contactorCache, shouldSaveToPhotos, containerWidth)

            // 未来可以在这里使用 cb 中的回调
            // 例如：cb.onAcceptFriendRequest?.(message)
        }
    }

    // ============== 公共辅助方法（从旧代码复制） ==============

    // Reaction count branch uses numeric-only display; no English text to translate.
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

            val emojis = message.reactions?.groupBy { it.emoji }

            emojis?.forEach { reaction ->
                val reactionItem = LayoutInflater.from(reactionsView.context)
                    .inflate(R.layout.chat_item_reaction, reactionsView, false)
                reactionItem.findViewById<AppCompatTextView>(R.id.tv_emoji).text = reaction.key
                val reactions = reaction.value.distinctBy { it.uid }
                if (reactions.size == 1) {
                    val id = reactions[0].uid
                    // 从缓存中获取联系人信息
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
                    showContent(tvTranslateContent, it.translatedContentEN ?: "", message)
                }

                TranslateStatus.ShowCN -> {
                    showContent(tvTranslateContent, it.translatedContentCN ?: "", message)
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

    /** Bind derived-content (inline translate / speech-to-text) with long-press copy + notice (PRD §4). */
    private fun showContent(
        textView: AppCompatTextView,
        content: String,
        sourceMessage: TextChatMessage,
    ) {
        textView.text = content
        textView.setOnLongClickListener {
            Util.copyToClipboard(textView.context, content)
            val conv = sourceMessage.forWhat
            // PRD v2.0 §改动2: derived text (translate / STT) is real content; trace only if it
            // belongs to someone else, judged by the ORIGINAL author (single-forward → inner author),
            // consistent with the main copy paths.
            val myId = globalServices.myId
            if (conv != null && sourceMessage.authorId.isNotEmpty() &&
                NoticeAggregator.copyCarriesForeignContent(listOf(sourceMessage), myId)
            ) {
                // PRD §5.1: derived-content copy (translate / STT) is NOT visible inside CF detail
                // per spec → mode is always UNKNOWN. Pass explicitly to keep reviewer awareness.
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
        val thirdColor = ContextCompat.getColor(timeContainer.context, com.difft.android.base.R.color.t_third)

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

            sendStatusImageView?.imageTintList = ContextCompat.getColorStateList(
                timeContainer.context,
                com.difft.android.base.R.color.t_third
            )

            readNumberTextView?.apply {
                background = ResUtils.getDrawable(R.drawable.chat_read_number_bg)
                setTextColor(thirdColor)
            }

            timeContainer.setRightMargin(0)
        }
    }

    private fun shouldShowMessageTimeShadow(message: TextChatMessage): Boolean {
        // Confidential messages: time needs background to stay readable over cover/blur
        if (message.isConfidential()) return true

        if (!message.reactions.isNullOrEmpty()) return false

        // Image/video messages (including single-forwarded image/video)
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

// ============================================================================
// Quote thumbnail rendering (⑤) — TOP-LEVEL package functions.
// Deliberately NOT instance methods of the Message ViewHolder: top-level placement means they
// capture neither `this` nor any ViewHolder field. The Message class exposes only the thin
// `quoteThumbnail` accessor + a `clearQuoteThumbnail()` recycle entry that delegates here.
// ============================================================================

/**
 * Monotonic per-bind recycle token source. A per-bind unique token (instead of `quote.id`, the
 * original timestamp) prevents two quotes of the SAME original — visible and recycled together —
 * from colliding on the same tag value and cross-applying each other's async thumbnail.
 */
private val quoteThumbnailTokenSeq = AtomicLong()

/**
 * Binds the first [Quote] attachment to [imageView] using a tiered source priority:
 *   1. voice (`flags==1` or [MediaUtil.isAudioType]) → mic icon (CENTER)
 *   2. image/video:
 *        a. inline thumbnail bytes (difft-android senders) → rounded Glide load (CENTER_CROP)
 *        b. else → async reverse-lookup of the LOCAL original message (iOS/Mac/TT senders carry no
 *           bytes); found on disk → rounded Glide load, else → hide the thumbnail (text-only).
 *   3. genuine file (pdf/doc/zip/etc.) → `ic_file` icon (CENTER, NEVER center-crop)
 *
 * Image-loading is gated behind [MediaUtil.isImageOrVideoType] so a real file never goes through
 * the center-crop path (which would render a stretched/oversized icon).
 *
 * @param forWhat the conversation scope, used to scope the reverse-lookup to this room. Derived
 *   from the ViewHolder's injected `forWhat` field (TextChatMessage has no roomId/roomType).
 */
internal fun bindQuoteThumbnail(imageView: ImageView, quote: Quote, forWhat: For?) {
    val qa = quote.attachments?.firstOrNull()
    if (qa == null) {
        clearQuoteThumbnail(imageView)
        return
    }

    val isVoice = qa.flags == 1 || MediaUtil.isAudioType(qa.contentType)
    // Per-bind unique recycle token (not quote.id) — see [quoteThumbnailTokenSeq].
    val token = quoteThumbnailTokenSeq.incrementAndGet()
    imageView.setTag(R.id.quote_thumbnail_job, token)
    when {
        isVoice -> {
            imageView.visibility = View.VISIBLE
            QuoteThumbnailBinder.setTypeIcon(imageView, R.drawable.chat_ic_quote_mic)
        }
        MediaUtil.isImageOrVideoType(qa.contentType) -> {
            val thumbBytes = qa.thumbnail?.thumbnail?.takeIf { it.isNotEmpty() }
            if (thumbBytes != null) {
                // difft-android inline-bytes path.
                imageView.visibility = View.VISIBLE
                QuoteThumbnailBinder.loadRoundedThumbnail(imageView, thumbBytes)
            } else {
                // iOS / Mac / TempTalk: no bytes on the wire → reverse-look-up the local original.
                // Stay GONE until/unless the lookup finds a file (avoids a flash of misleading icon).
                // clearQuoteThumbnail nulls the recycle tag, so re-set it after the clear.
                clearQuoteThumbnail(imageView)
                imageView.setTag(R.id.quote_thumbnail_job, token)
                resolveOriginalThumbnailAsync(imageView, quote.id, forWhat?.id, forWhat?.typeValue ?: 0, token)
            }
        }
        else -> {
            // Genuine file (pdf/doc/zip/etc.) → file icon, CENTER (never center-crop).
            imageView.visibility = View.VISIBLE
            QuoteThumbnailBinder.setTypeIcon(imageView, R.drawable.ic_file)
        }
    }
}

/**
 * Reverse-looks-up the local original message (by timestamp + room) on [Dispatchers.IO] and, if a
 * local image/video file is found, loads it as the quote thumbnail. Runs on the host Activity's
 * lifecycle scope (resolved via [getLifecycleOwner] at bind time — NOT the fragment-view scope), so
 * the launch is NOT auto-cancelled on item detach / recycle; staleness is instead guarded by
 * re-checking the recycle [token] (the `quote_thumbnail_job` tag) and [isHostActivityAlive] before
 * touching the view. No-ops (leaves text-only) when no original is found locally.
 */
private fun resolveOriginalThumbnailAsync(
    imageView: ImageView,
    originalTimestamp: Long,
    roomId: String?,
    roomType: Int,
    token: Long,
) {
    if (roomId.isNullOrEmpty()) return
    // getLifecycleOwner() resolves via view-tree first, then the context chain (Activity) — needed
    // because findViewTreeLifecycleOwner() alone is null during onBindViewHolder (item not yet
    // attached), which would otherwise make the image reverse-lookup never run.
    val owner = imageView.getLifecycleOwner() ?: return
    owner.lifecycleScope.launch {
        val path = withContext(Dispatchers.IO) {
            findOriginalAttachmentPath(originalTimestamp, roomId, roomType)
        } ?: return@launch
        // Back on Main: only apply if this view still represents the same quote and host is alive.
        if (imageView.getTag(R.id.quote_thumbnail_job) == token && imageView.isHostActivityAlive()) {
            imageView.visibility = View.VISIBLE
            // Encrypted-at-rest images resolve to a decrypting content uri; legacy plaintext stays a File.
            QuoteThumbnailBinder.loadRoundedThumbnail(imageView, EncryptedAttachmentAccess.imageGlideModel(path))
        }
    }
}

/**
 * Finds the on-disk path of the original quoted message's image/video attachment, forward-aware.
 * - Normal attachment message: file under `getMessageAttachmentFilePath(message.id)`.
 * - Single-forward message: the forwarded file lives under the attachment's `authorityId` directory
 *   (NOT message.id) — see generateMessageFromForward / ChatMessageListFragment image-preview.
 * Returns the path only if the file exists AND the attachment is image/video; else null (text-only).
 */
internal fun findOriginalAttachmentPath(timestamp: Long, roomId: String, roomType: Int): String? = runCatching {
    val original = wcdb.message.getFirstObject(
        DBMessageModel.roomId.eq(roomId)
            .and(DBMessageModel.roomType.eq(roomType))
            .and(DBMessageModel.timeStamp.eq(timestamp))
    ) ?: return null

    // Single-forward original → forwarded attachment under its authorityId directory.
    val forward = original.forwardContext()?.forwards?.takeIf { it.size == 1 }?.firstOrNull()
    if (forward != null) {
        val att = forward.attachments?.firstOrNull() ?: return null
        if (!MediaUtil.isImageOrVideoType(att.contentType)) return null
        val fileName = att.fileName ?: return null
        val path = FileUtil.getMessageAttachmentFilePath(att.authorityId.toString()) + fileName
        // isReadable (not File.exists): encrypted-at-rest media has only the .encrypt on disk; the
        // loader resolves it to a decrypting content uri via imageGlideModel.
        return path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
    }

    // Normal attachment original → file under message.id directory.
    val att = original.attachment()?.takeIf { MediaUtil.isImageOrVideoType(it.contentType) } ?: return null
    val fileName = att.fileName ?: return null
    val path = FileUtil.getMessageAttachmentFilePath(original.id) + fileName
    path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
}.onFailure {
    L.w { "[QuoteThumb] findOriginalAttachmentPath failed ts=$timestamp: ${it.stackTraceToString()}" }
}.getOrNull()

/** Clears the thumbnail [imageView]: invalidates any in-flight lookup, clears Glide and hides it. */
internal fun clearQuoteThumbnail(imageView: ImageView) {
    imageView.setTag(R.id.quote_thumbnail_job, null)
    if (imageView.isHostActivityAlive()) Glide.with(imageView).clear(imageView)
    imageView.setImageDrawable(null)
    imageView.visibility = View.GONE
}
