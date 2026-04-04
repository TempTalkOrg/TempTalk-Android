package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
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

/** High blur for image/video (rich content needs stronger blur), low blur for others (preserve content outline) */
private const val BLUR_RADIUS_HIGH = 80f
private const val BLUR_RADIUS_LOW = 16f

@RequiresApi(Build.VERSION_CODES.S)
private fun View.applyConfidentialBlur(radius: Float = BLUR_RADIUS_LOW, cornerRadius: Float = 0f) {
    if (cornerRadius > 0f) {
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
    }
    clipToOutline = true
    setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
}

private fun View.clearConfidentialBlur() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRenderEffect(null)
        clipToOutline = false
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }
}

private fun View.setupConfidentialCover(isBlurred: Boolean) {
    visibility = View.VISIBLE
    if (isBlurred) {
        setBackgroundColor(Color.TRANSPARENT)
        (this as? TextView)?.text = null
    } else {
        setBackgroundResource(R.drawable.chat_message_confidential_bg)
    }
}


/**
 * 文本内容绑定器
 *
 * 从 TextContentAdapter 简化而来，只保留核心的文本渲染逻辑
 */
object TextContentBinder : ContentBinder {

    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val textView = contentFrame.findViewById<com.difft.android.chat.ui.textpreview.SelectableTextView>(R.id.textView)
        val llContent = contentFrame.findViewById<View>(R.id.ll_content)
        val coverView = contentFrame.findViewById<View>(R.id.v_cover)
        val rawText = textMessage.message.toString()

        textView.autoLinkMask = 0
        textView.textSize = if (TextSizeUtil.isLarger) 24f else 16f

        if (textMessage.isConfidential()) {
            // Render text normally for blur visual, but skip truncation/interaction — coverView handles everything
            LinkTextUtils.setMarkdownToTextview(textView.context, rawText, textView, textMessage.mentions)
            textView.maxLines = TextTruncationUtil.DEFAULT_MAX_LINES
            textView.movementMethod = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                llContent?.applyConfidentialBlur()
                coverView.setupConfidentialCover(isBlurred = true)

            } else {
                coverView.setupConfidentialCover(isBlurred = false)

            }
            coverView.setOnClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performClick()
            }
            coverView.setOnLongClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performLongClick()
                true
            }
        } else {
            TextTruncationUtil.setupDoubleClickPreview(textView, rawText, textMessage.mentions, textMessage)

            val messageId = textMessage.id
            textView.setTag(R.id.tag_truncation_message_id, messageId)

            LinkTextUtils.setMarkdownToTextview(
                textView.context,
                rawText,
                textView,
                textMessage.mentions
            )

            textView.maxLines = TextTruncationUtil.DEFAULT_MAX_LINES + 1

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

            llContent?.clearConfidentialBlur()
            coverView.visibility = View.GONE

            coverView.setOnClickListener(null)
            coverView.setOnLongClickListener(null)
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

            // Skip double-click preview for confidential messages; coverView handles clicks
            if (!textMessage.isConfidential()) {
                // Save message ID to tag for ViewHolder reuse detection in post callbacks
                val messageId = textMessage.id
                val rawText = textMessage.message.toString()
                textView.setTag(R.id.tag_truncation_message_id, messageId)

                // Non-confidential: set up double-click text preview
                TextTruncationUtil.setupDoubleClickPreview(textView, rawText, textMessage.mentions, textMessage)

                // First render full text to measure line count
                LinkTextUtils.setMarkdownToTextview(
                    textView.context,
                    rawText,
                    textView,
                    textMessage.mentions
                )

                // Set to DEFAULT_MAX_LINES + 1 initially to prevent flicker on refresh
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
                // Confidential: plain text display, clicks handled by coverView
                textView.text = textMessage.message
            }
        } else {
            textView.visibility = View.GONE
        }

        if (textMessage.isConfidential()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                contentFrame.findViewById<View>(R.id.ll_content)?.applyConfidentialBlur(BLUR_RADIUS_HIGH, 6.dp.toFloat())
                coverView.setupConfidentialCover(isBlurred = true)

            } else {
                coverView.setupConfidentialCover(isBlurred = false)

            }
        } else {
            contentFrame.findViewById<View>(R.id.ll_content)?.clearConfidentialBlur()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                voiceMessageView.applyConfidentialBlur()
                coverView.setupConfidentialCover(isBlurred = true)

            } else {
                coverView.setupConfidentialCover(isBlurred = false)

            }
            coverView.setOnClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performClick()
            }
            coverView.setOnLongClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performLongClick()
                true
            }
        } else {
            voiceMessageView.clearConfidentialBlur()
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
        val llContent = contentFrame.findViewById<View>(R.id.ll_content)
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
                attachContentView.visibility = View.GONE
            } else {
                attachContentView.visibility = View.VISIBLE
                attachContentView.setupAttachmentView(textMessage)
            }
        } else {
            attachContentView.visibility = View.VISIBLE
            attachContentView.setupAttachmentView(textMessage)
        }

        // Text rendering (confidential messages also render normally; coverView handles masking)
        if (!TextUtils.isEmpty(textMessage.message)) {
            textView.visibility = View.VISIBLE
            textView.autoLinkMask = 0

            if (!textMessage.isConfidential()) {
                val messageId = textMessage.id
                val rawText = textMessage.message.toString()
                textView.setTag(R.id.tag_truncation_message_id, messageId)

                TextTruncationUtil.setupDoubleClickPreview(textView, rawText, textMessage.mentions, textMessage)

                LinkTextUtils.setMarkdownToTextview(
                    textView.context,
                    rawText,
                    textView,
                    textMessage.mentions
                )

                textView.maxLines = TextTruncationUtil.DEFAULT_MAX_LINES + 1

                textView.doOnPreDraw {
                    TextTruncationUtil.applyTruncation(
                        context = textView.context,
                        textView = textView,
                        messageId = messageId
                    ) {
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
            } else {
                textView.text = textMessage.message
            }
        } else {
            textView.visibility = View.GONE
        }

        // Confidential cover (unified handling for all attach types including long text)
        if (textMessage.isConfidential()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                llContent?.applyConfidentialBlur()
                coverView.setupConfidentialCover(isBlurred = true)

            } else {
                coverView.setupConfidentialCover(isBlurred = false)

            }
            coverView.setOnClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performClick()
            }
            coverView.setOnLongClickListener {
                LinkTextUtils.findParentChatMessageItemView(coverView)?.performLongClick()
                true
            }
        } else {
            llContent?.clearConfidentialBlur()
            coverView.visibility = View.GONE

            coverView.setOnClickListener(null)
            coverView.setOnLongClickListener(null)
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

        // Get contact info from cache
        val contactor = contactorCache.getContactor(id)
        if (contactor != null) {
            avatarView.setAvatar(contactor)
        } else {
            // Cache miss, show default avatar
            avatarView.setAvatar(
                null,
                null,
                com.difft.android.chat.contacts.data.ContactorUtil.getFirstLetter(name),
                id
            )
        }

        val llContent = contentFrame.findViewById<View>(R.id.ll_content)
        if (chatMessage.isConfidential()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                llContent?.applyConfidentialBlur()
                coverView.setupConfidentialCover(isBlurred = true)

            } else {
                coverView.setupConfidentialCover(isBlurred = false)

            }
        } else {
            llContent?.clearConfidentialBlur()
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

        // Set title
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

        // Set preview list
        val adapter = ForwardMessagesAdapter(contactorCache)
        rvForwardHistory.adapter = adapter
        rvForwardHistory.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        rvForwardHistory.setOnTouchListener { view, event ->
            LinkTextUtils.findParentChatMessageItemView(view)?.onTouchEvent(event) ?: false
        }
        adapter.submitList(forwards.take(5))

        if (textMessage.isConfidential()) {
            val llContent = contentFrame.findViewById<View>(R.id.ll_content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Clear margin to prevent gap between blur area and container
                (llContent?.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
                llContent?.applyConfidentialBlur()
                vCover.setupConfidentialCover(isBlurred = true)

            } else {
                vCover.setupConfidentialCover(isBlurred = false)

            }
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
            val llContent = contentFrame.findViewById<View>(R.id.ll_content)
            llContent?.clearConfidentialBlur()
            val margin = 8.dp
            (llContent?.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(margin, margin, margin, margin)
            vCover.visibility = View.GONE

        }
    }
}