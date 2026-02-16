package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.common.TextTruncationUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.chat.widget.ImageAndVideoMessageView
import com.difft.android.chat.widget.VoiceMessageView
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.isLongText

/**
 * 文本内容绑定器
 *
 * 从 TextContentAdapter 简化而来，只保留核心的文本渲染逻辑
 */
object TextContentBinder : ContentBinder {

    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val textView = contentFrame.findViewById<com.difft.android.chat.ui.textpreview.SelectableTextView>(R.id.textView)
        val rawText = textMessage.message.toString()

        textView.autoLinkMask = 0

        textView.textSize = if (TextSizeUtil.isLarger) 24f else 16f

        val content = textMessage.message.toString()

        if (textMessage.isConfidential()) {
            // 机密消息不设置双击预览，点击由 ChatMessageListFragment 统一处理机密消息弹窗
            textView.movementMethod = null
            val spannableText = getGrayBlockText(
                content,
                ContextCompat.getColor(
                    textView.context,
                    com.difft.android.base.R.color.bg_confidential
                ),
                textView.width,
                textView.paint
            )
            textView.text = spannableText
            textView.maxLines = 5
        } else {
            // 非机密消息：设置双击打开文本预览
            TextTruncationUtil.setupDoubleClickPreview(textView, rawText, textMessage.mentions, textMessage)

            // 保存消息 ID 到 tag，用于在 post 回调中检查 View 是否被复用
            val messageId = textMessage.id
            textView.setTag(R.id.tag_truncation_message_id, messageId)

            // First render full text to measure line count
            LinkTextUtils.setMarkdownToTextview(
                textView.context,
                rawText,
                textView,
                textMessage.mentions
            )

            // 先设置为 DEFAULT_MAX_LINES + 1 行，避免刷新时闪动
            textView.maxLines = TextTruncationUtil.DEFAULT_MAX_LINES + 1

            // doOnPreDraw to measure after layout and apply truncation
            textView.doOnPreDraw {
                TextTruncationUtil.applyTruncation(
                    context = textView.context,
                    textView = textView,
                    messageId = messageId
                ) {
                    TextTruncationUtil.showFullTextDialog(
                        textView,
                        rawText,
                        textMessage.mentions,
                        textMessage
                    )
                }
            }
        }
    }

    /**
     * 将文本渲染为灰色块样式（用于机密消息）
     * 供外部调用（如 AttachContentBinder 处理长文本机密消息）
     */
    fun getGrayBlockText(
        originalText: String,
        blockColor: Int,
        textViewWidth: Int,
        paint: Paint
    ): SpannableStringBuilder {
        val spannableStringBuilder = SpannableStringBuilder(originalText)

        var start = 0
        while (start < originalText.length) {
            var end = start
            while (end < originalText.length && originalText[end] != ' ') {
                end++
            }

            while (paint.measureText(originalText, start, end) > textViewWidth) {
                var splitEnd = start
                while (splitEnd < end && paint.measureText(originalText, start, splitEnd) <= textViewWidth) {
                    splitEnd++
                }
                if (splitEnd > start) {
                    spannableStringBuilder.setSpan(
                        UniformBackgroundSpan(blockColor, 26.dp),
                        start,
                        splitEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                start = splitEnd
            }

            if (end > start) {
                spannableStringBuilder.setSpan(
                    UniformBackgroundSpan(blockColor, 26.dp),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            start = end + 1
        }

        return spannableStringBuilder
    }

    class UniformBackgroundSpan(
        private val backgroundColor: Int,
        private val blockHeight: Int
    ) : ReplacementSpan() {

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val textWidth = paint.measureText(text, start, end).toInt()
            if (fm != null) {
                val original = paint.fontMetricsInt
                val realTextHeight = original.descent - original.ascent

                val extra = (blockHeight - realTextHeight).coerceAtLeast(0) / 2

                fm.ascent = original.ascent - extra
                fm.descent = original.descent + extra
                fm.top = fm.ascent
                fm.bottom = fm.descent
            }
            return textWidth
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val originalColor = paint.color

            val rectTop = y + paint.fontMetrics.top
            val rectBottom = y + paint.fontMetrics.bottom

            paint.color = backgroundColor
            canvas.drawRect(
                x, rectTop, x + paint.measureText(text, start, end), rectBottom, paint
            )

            paint.color = Color.TRANSPARENT
            canvas.drawText(text, start, end, x, y.toFloat(), paint)

            paint.color = originalColor
        }
    }
}

/**
 * 图片/视频内容绑定器
 *
 * 从 ImageContentAdapter 简化而来
 */
object ImageContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val imageMessageView = contentFrame.findViewById<ImageAndVideoMessageView>(R.id.imageMessageView)
        val textView = contentFrame.findViewById<TextView>(R.id.textView)
        val coverView = contentFrame.findViewById<TextView>(R.id.v_cover)

        imageMessageView.setupImageView(textMessage, shouldSaveToPhotos, containerWidth)

        if (!TextUtils.isEmpty(textMessage.message)) {
            textView.visibility = View.VISIBLE

            textView.autoLinkMask = 0

            // 机密消息不设置双击预览，coverView 会覆盖在上面处理点击
            if (!textMessage.isConfidential()) {
                // 保存消息 ID 到 tag，用于在 post 回调中检查 View 是否被复用
                val messageId = textMessage.id
                val rawText = textMessage.message.toString()
                textView.setTag(R.id.tag_truncation_message_id, messageId)

                // 非机密消息：设置双击打开文本预览
                TextTruncationUtil.setupDoubleClickPreview(textView, rawText, textMessage.mentions, textMessage)

                // First render full text to measure line count
                LinkTextUtils.setMarkdownToTextview(
                    textView.context,
                    rawText,
                    textView,
                    textMessage.mentions
                )

                // 先设置为 DEFAULT_MAX_LINES + 1 行，避免刷新时闪动
                textView.maxLines = TextTruncationUtil.DEFAULT_MAX_LINES + 1

                // doOnPreDraw to measure after layout and apply truncation
                textView.doOnPreDraw {
                    TextTruncationUtil.applyTruncation(
                        context = textView.context,
                        textView = textView,
                        messageId = messageId
                    ) {
                        TextTruncationUtil.showFullTextDialog(
                            textView,
                            rawText,
                            textMessage.mentions,
                            textMessage
                        )
                    }
                }
            } else {
                // 机密消息：简单显示文本，点击由 coverView 处理
                textView.text = textMessage.message
            }
        } else {
            textView.visibility = View.GONE
        }

        if (textMessage.isConfidential()) {
            coverView.visibility = View.VISIBLE
        } else {
            coverView.visibility = View.GONE
        }
    }
}

/**
 * 语音消息内容绑定器
 *
 * 从 AudioContentAdapter 简化而来
 */
object AudioContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val voiceMessageView = contentFrame.findViewById<VoiceMessageView>(R.id.voice_message_view)
        val coverView = contentFrame.findViewById<TextView>(R.id.v_cover)

        voiceMessageView.setAudioMessage(textMessage)

        if (textMessage.playStatus == AudioMessageManager.PLAY_STATUS_NOT_PLAY) {
            voiceMessageView.setProgressColor(
                ContextCompat.getColor(
                    voiceMessageView.context,
                    com.difft.android.base.R.color.t_info
                )
            )
            voiceMessageView.setCursorColor(
                ContextCompat.getColor(
                    voiceMessageView.context,
                    com.difft.android.base.R.color.t_info
                )
            )
        } else {
            voiceMessageView.setProgressColor(
                ContextCompat.getColor(
                    voiceMessageView.context,
                    com.difft.android.base.R.color.icon
                )
            )
            voiceMessageView.setCursorColor(
                ContextCompat.getColor(
                    voiceMessageView.context,
                    com.difft.android.base.R.color.icon
                )
            )
        }

        if (textMessage.isConfidential()) {
            coverView.visibility = View.VISIBLE
            coverView.setOnClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performClick()
            }
            coverView.setOnLongClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performLongClick()
                true
            }
        } else {
            coverView.visibility = View.GONE
            coverView.setOnClickListener(null)
            coverView.setOnLongClickListener(null)
        }
    }
}

/**
 * 附件内容绑定器
 *
 * 从 AttachContentAdapter 简化而来
 */
object AttachContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val attachContentView = contentFrame.findViewById<com.difft.android.chat.widget.AttachMessageView>(
            R.id.attach_content_view
        )
        val textView = contentFrame.findViewById<TextView>(R.id.textView)
        val coverView = contentFrame.findViewById<View>(R.id.v_cover)

        val attachment = textMessage.attachment

        // Handle long text attachment - check if file is downloaded
        val isLongText = attachment?.isLongText() == true
        var longTextPath: String? = null
        var isLongTextDownloaded = false

        if (isLongText) {
            val fileName = attachment?.fileName ?: ""
            longTextPath = FileUtil.getMessageAttachmentFilePath(textMessage.id) + fileName
            val isCurrentDeviceSend = textMessage.isMine && textMessage.id.last().digitToIntOrNull() == DEFAULT_DEVICE_ID
            val isFileValid = FileUtil.isFileValid(longTextPath)
            val progress = textMessage.getAttachmentProgress()

            isLongTextDownloaded = isFileValid && (attachment?.status == AttachmentStatus.SUCCESS.code || progress == 100 || isCurrentDeviceSend)

            if (isLongTextDownloaded) {
                // File downloaded - hide attachment view
                attachContentView.visibility = View.GONE
            } else {
                // File not downloaded yet - show attachment view for download
                attachContentView.visibility = View.VISIBLE
                attachContentView.setupAttachmentView(textMessage)
            }
        } else {
            // Normal attachment handling
            attachContentView.visibility = View.VISIBLE
            attachContentView.setupAttachmentView(textMessage)
        }

        // 长文本已下载的机密消息：按文本机密消息处理
        val isLongTextConfidential = isLongTextDownloaded && textMessage.isConfidential()

        if (!TextUtils.isEmpty(textMessage.message)) {
            textView.visibility = View.VISIBLE
            textView.autoLinkMask = 0

            if (isLongTextConfidential) {
                // 长文本机密消息：使用灰色块样式，点击传递给父容器处理（由 ChatMessageListFragment 统一处理转发跳转）
                textView.movementMethod = null
                val content = textMessage.message.toString()
                val spannableText = TextContentBinder.getGrayBlockText(
                    content,
                    ContextCompat.getColor(textView.context, com.difft.android.base.R.color.bg_confidential),
                    textView.width,
                    textView.paint
                )
                textView.text = spannableText
                textView.maxLines = 5

                // 点击传递给父容器处理（与普通文本机密消息一致）
                textView.setOnClickListener {
                    LinkTextUtils.findParentChatMessageItemView(textView)?.performClick()
                }
                textView.setOnLongClickListener {
                    LinkTextUtils.findParentChatMessageItemView(textView)?.performLongClick()
                    true
                }
            } else if (textMessage.isConfidential()) {
                // 普通机密附件消息：简单显示文本，点击由 coverView 处理
                textView.text = textMessage.message
            } else {
                // 非机密消息：普通文本处理
                // 保存消息 ID 到 tag，用于在 post 回调中检查 View 是否被复用
                val messageId = textMessage.id
                val rawText = textMessage.message.toString()
                textView.setTag(R.id.tag_truncation_message_id, messageId)

                // 设置双击打开文本预览
                TextTruncationUtil.setupDoubleClickPreview(textView, rawText, textMessage.mentions, textMessage)

                // First render full text to measure line count
                LinkTextUtils.setMarkdownToTextview(
                    textView.context,
                    rawText,
                    textView,
                    textMessage.mentions
                )

                // 先设置为 DEFAULT_MAX_LINES + 1 行，避免刷新时闪动
                textView.maxLines = TextTruncationUtil.DEFAULT_MAX_LINES + 1

                // doOnPreDraw to measure after layout and apply truncation
                textView.doOnPreDraw {
                    TextTruncationUtil.applyTruncation(
                        context = textView.context,
                        textView = textView,
                        messageId = messageId
                    ) {
                        // View more click - read full file content if long text is downloaded
                        if (isLongTextDownloaded && longTextPath != null) {
                            TextTruncationUtil.showFullTextDialogFromFile(
                                textView,
                                longTextPath,
                                rawText,
                                textMessage.mentions,
                                textMessage
                            )
                        } else {
                            TextTruncationUtil.showFullTextDialog(
                                textView,
                                rawText,
                                textMessage.mentions,
                                textMessage
                            )
                        }
                    }
                }
            }
        } else {
            textView.visibility = View.GONE
        }

        // 机密消息遮罩处理（长文本已下载的机密消息不需要遮罩，因为文本已经用灰色块处理）
        // 点击传递给父容器，由 ChatMessageListFragment 统一处理（与 ImageContentBinder 保持一致）
        if (textMessage.isConfidential() && !isLongTextConfidential) {
            coverView.visibility = View.VISIBLE
            coverView.setOnClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performClick()
            }
            coverView.setOnLongClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performLongClick()
                true
            }
        } else {
            coverView.visibility = View.GONE
        }
    }
}

/**
 * 联系人卡片内容绑定器
 *
 * 从 ContactContentAdapter 简化而来
 */
object ContactContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val chatMessage = message as TextChatMessage
        val contacts = chatMessage.sharedContacts
        if (contacts.isNullOrEmpty()) return

        val sharedContact = contacts[0]
        val avatarView = contentFrame.findViewById<com.difft.android.chat.common.AvatarView>(R.id.imageview_avatar)
        val tvName = contentFrame.findViewById<AppCompatTextView>(R.id.tv_name)
        val coverView = contentFrame.findViewById<View>(R.id.v_cover)

        val id = sharedContact.phone?.firstOrNull()?.value ?: ""
        val name = sharedContact.name?.displayName
        tvName.text = name

        // 从缓存中获取联系人信息
        val contactor = contactorCache.getContactor(id)
        if (contactor != null) {
            avatarView.setAvatar(contactor)
        } else {
            // 缓存miss，显示默认头像
            avatarView.setAvatar(
                null,
                null,
                com.difft.android.chat.contacts.data.ContactorUtil.getFirstLetter(name),
                id
            )
        }

        if (chatMessage.isConfidential()) {
            coverView.visibility = View.VISIBLE
        } else {
            coverView.visibility = View.GONE
        }
    }
}

/**
 * 通知消息内容绑定器
 *
 * 从 NotifyContentAdapter 简化而来
 *
 * 注意：由于通知消息有交互（按钮点击），需要传入回调
 */
class NotifyContentBinder() : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textChatMessage = message as com.difft.android.chat.message.NotifyChatMessage
        val textViewContent = contentFrame.findViewById<TextView>(R.id.tv_content)
        val textViewAction = contentFrame.findViewById<TextView>(R.id.tv_action)

        textViewAction.visibility = View.GONE
        textViewContent.text = textChatMessage.notifyMessage?.showContent
    }
}

/**
 * 截屏消息内容绑定器
 *
 * 复用 notify 布局，显示截屏通知文本
 */
object ScreenShotContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val textViewContent = contentFrame.findViewById<TextView>(R.id.tv_content)
        val textViewAction = contentFrame.findViewById<TextView>(R.id.tv_action)

        textViewAction.visibility = View.GONE
        textViewContent.text = textMessage.message
    }
}

/**
 * Content binder for confidential message placeholder.
 *
 * Reuses notify layout (centered gray text). Shown to sender after recipient reads; batch-deleted when sender leaves the page.
 * Change to a dedicated layout if placeholder style needs to diverge from notify later.
 */
object ConfidentialPlaceholderContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textViewContent = contentFrame.findViewById<TextView>(R.id.tv_content)
        val textViewAction = contentFrame.findViewById<TextView>(R.id.tv_action)
        textViewAction.visibility = View.GONE
        textViewContent.text = contentFrame.context.getString(R.string.chat_confidential_message_viewed)
    }
}

/**
 * 合并转发消息内容绑定器
 *
 * 处理多条消息的合并转发显示
 */
object MultiForwardContentBinder : ContentBinder {
    @SuppressLint("ClickableViewAccessibility")
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val forwardContext = textMessage.forwardContext ?: return
        val forwards = forwardContext.forwards ?: return

        val tvMultiTitle = contentFrame.findViewById<androidx.appcompat.widget.AppCompatTextView>(R.id.tv_multi_title)
        val rvForwardHistory = contentFrame.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_forward_history)
        val vCover = contentFrame.findViewById<View>(R.id.v_cover)

        // 设置标题
        val context = contentFrame.context
        if (forwards.firstOrNull()?.isFromGroup == true) {
            tvMultiTitle.text = context.getString(R.string.group_chat_history)
        } else {
            val authorId = forwards.firstOrNull()?.author ?: ""
            val author = contactorCache.getContactor(authorId)
            tvMultiTitle.text = if (author != null) {
                context.getString(R.string.chat_history_for, author.getDisplayNameWithoutRemarkForUI())
            } else {
                context.getString(R.string.chat_history_for, authorId.formatBase58Id())
            }
        }

        // 设置预览列表
        val adapter = ForwardMessagesAdapter(contactorCache)
        rvForwardHistory.adapter = adapter
        rvForwardHistory.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        rvForwardHistory.setOnTouchListener { view, event ->
            LinkTextUtils.findParentChatMessageItemView(view)?.onTouchEvent(event) ?: false
        }
        adapter.submitList(forwards.take(5))

        // 机密消息遮罩（覆盖整个合并转发区域）
        if (textMessage.isConfidential()) {
            vCover.visibility = View.VISIBLE
            // 点击遮罩触发父视图的点击事件
            vCover.setOnClickListener {
                LinkTextUtils.findParentChatMessageItemView(vCover)?.let { parent ->
                    parent.findViewById<View>(R.id.contentContainer)?.performClick()
                }
            }
            vCover.setOnLongClickListener {
                LinkTextUtils.findParentChatMessageItemView(vCover)?.let { parent ->
                    parent.findViewById<View>(R.id.contentContainer)?.performLongClick()
                }
                true
            }
        } else {
            vCover.visibility = View.GONE
        }
    }
}