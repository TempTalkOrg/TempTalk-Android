package com.difft.android.chat.ui

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
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.common.TextTruncationUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.chat.widget.ImageAndVideoMessageView
import com.difft.android.chat.widget.VoiceMessageView

/**
 * 文本内容绑定器
 *
 * 从 TextContentAdapter 简化而来，只保留核心的文本渲染逻辑
 */
object TextContentBinder : ContentBinder {

    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil) {
        val textMessage = message as TextChatMessage
        val textView = contentFrame.findViewById<AppCompatTextView>(R.id.textView)

        textView.setOnClickListener {
            LinkTextUtils.findParentChatMessageItemView(textView)?.performClick()
        }
        textView.setOnLongClickListener {
            LinkTextUtils.findParentChatMessageItemView(textView)?.performLongClick()
            true
        }

        textView.autoLinkMask = 0

        textView.textSize = if (TextSizeUtil.isLarger) 24f else 16f

        val content = textMessage.message.toString()

        if (textMessage.isConfidential()) {
            textView.movementMethod = null
            val spannableText = getGrayBlockTextWithTransparentForeground(
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
            // 保存消息 ID 到 tag，用于在 post 回调中检查 View 是否被复用
            val messageId = textMessage.id
            val rawText = textMessage.message.toString()
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

            // Post to measure after layout and apply truncation
            textView.post {
                TextTruncationUtil.applyTruncationWithMarkdown(
                    context = textView.context,
                    textView = textView,
                    messageId = messageId,
                    rawText = rawText,
                    mentions = textMessage.mentions
                ) {
                    TextTruncationUtil.showFullTextDialog(
                        textView,
                        rawText,
                        textMessage.mentions
                    )
                }
            }
        }
    }

    private fun getGrayBlockTextWithTransparentForeground(
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
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil) {
        val textMessage = message as TextChatMessage
        val imageMessageView = contentFrame.findViewById<ImageAndVideoMessageView>(R.id.imageMessageView)
        val textView = contentFrame.findViewById<TextView>(R.id.textView)
        val coverView = contentFrame.findViewById<TextView>(R.id.v_cover)

        imageMessageView.setupImageView(textMessage)

        if (!TextUtils.isEmpty(textMessage.message)) {
            textView.visibility = View.VISIBLE

            textView.autoLinkMask = 0

            // 保存消息 ID 到 tag，用于在 post 回调中检查 View 是否被复用
            val messageId = textMessage.id
            val rawText = textMessage.message.toString()
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

            // Post to measure after layout and apply truncation
            textView.post {
                TextTruncationUtil.applyTruncationWithMarkdown(
                    context = textView.context,
                    textView = textView,
                    messageId = messageId,
                    rawText = rawText,
                    mentions = textMessage.mentions
                ) {
                    TextTruncationUtil.showFullTextDialog(
                        textView,
                        rawText,
                        textMessage.mentions
                    )
                }
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
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil) {
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
        } else {
            coverView.visibility = View.GONE
            coverView.setOnClickListener(null)
        }
    }
}

/**
 * 附件内容绑定器
 *
 * 从 AttachContentAdapter 简化而来
 */
object AttachContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil) {
        val textMessage = message as TextChatMessage
        val attachContentView = contentFrame.findViewById<com.difft.android.chat.widget.AttachMessageView>(
            R.id.attach_content_view
        )
        val textView = contentFrame.findViewById<TextView>(R.id.textView)
        val coverView = contentFrame.findViewById<View>(R.id.v_cover)

        attachContentView.setupAttachmentView(textMessage)

        if (!TextUtils.isEmpty(textMessage.message)) {
            textView.visibility = View.VISIBLE

            textView.autoLinkMask = 0

            // 保存消息 ID 到 tag，用于在 post 回调中检查 View 是否被复用
            val messageId = textMessage.id
            val rawText = textMessage.message.toString()
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

            // Post to measure after layout and apply truncation
            textView.post {
                TextTruncationUtil.applyTruncationWithMarkdown(
                    context = textView.context,
                    textView = textView,
                    messageId = messageId,
                    rawText = rawText,
                    mentions = textMessage.mentions
                ) {
                    TextTruncationUtil.showFullTextDialog(
                        textView,
                        rawText,
                        textMessage.mentions
                    )
                }
            }
        } else {
            textView.visibility = View.GONE
        }

        if (textMessage.isConfidential()) {
            coverView.visibility = View.VISIBLE
            coverView.setOnClickListener {
                attachContentView.openFile()
                com.difft.android.chat.recent.RecentChatUtil.emitConfidentialRecipient(textMessage)
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
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil) {
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
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil) {
        val textChatMessage = message as com.difft.android.chat.message.NotifyChatMessage
        val textViewContent = contentFrame.findViewById<TextView>(R.id.tv_content)
        val textViewAction = contentFrame.findViewById<TextView>(R.id.tv_action)

        textViewAction.visibility = View.GONE
        textViewContent.text = textChatMessage.notifyMessage?.showContent
    }
}