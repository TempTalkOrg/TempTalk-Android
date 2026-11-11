package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.TextViewCompat
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.common.TextTruncationUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.chat.widget.ConfidentialAttachmentStatusView
import com.difft.android.chat.widget.ImageAndVideoMessageView
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.widget.VoiceMessageView
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import difft.android.messageserialization.model.isLongText
import kotlin.math.roundToInt


private const val BLUR_RADIUS_HIGH = 80f
private const val BLUR_RADIUS_LOW = 16f


private const val CONFIDENTIAL_COVER_RADIUS_DP = 16f


private const val CONTACT_CARD_AVATAR_SIZE_DP = 40
private val CONTACT_CARD_AVATAR_LETTER_TEXT_SIZE_DP = AvatarView.letterTextSizeDpFor(CONTACT_CARD_AVATAR_SIZE_DP)

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
    } else {
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = CONFIDENTIAL_COVER_RADIUS_DP * resources.displayMetrics.density
            setColor(bubblePalette().fill)
        }
    }
}




private const val BODY_TEXT_SP = 16f
private const val LARGE_TEXT_SP = 24f
private const val BODY_LINE_HEIGHT_SP = 24f
private const val LARGE_LINE_HEIGHT_SP = 36f






private fun bindBubbleText(textView: TextView, message: TextChatMessage, rawText: String) {
    val palette = textView.bubblePalette()
    textView.setTextColor(palette.text)
    textView.setLinkTextColor(palette.link)
    val lineHeightSp = if (TextSizeUtil.isLarger) LARGE_LINE_HEIGHT_SP else BODY_LINE_HEIGHT_SP
    TextViewCompat.setLineHeight(
        textView,
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineHeightSp, textView.resources.displayMetrics).roundToInt(),
    )
    LinkTextUtils.setMarkdownToTextview(textView.context, rawText, textView, message.mentions, palette.link, palette.linkMedium)
}

object TextContentBinder : ContentBinder {

    override fun rebindForWidth(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) =
        bind(contentFrame, message, contactorCache, shouldSaveToPhotos, containerWidth)

    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val textView = contentFrame.findViewById<com.difft.android.chat.ui.textpreview.SelectableTextView>(R.id.textView)
        val llContent = contentFrame.findViewById<View>(R.id.ll_content)
        val coverView = contentFrame.findViewById<View>(R.id.v_cover)
        val rawText = textMessage.message.toString()

        textView.autoLinkMask = 0
        textView.textSize = if (TextSizeUtil.isLarger) LARGE_TEXT_SP else BODY_TEXT_SP

        val isConfidential = textMessage.isConfidential()
        val messageId = textMessage.id
        val effectiveMaxLines = if (isConfidential) TextTruncationUtil.CONFIDENTIAL_MAX_LINES else TextTruncationUtil.DEFAULT_MAX_LINES

        textView.setTag(R.id.tag_truncation_message_id, messageId)
        bindBubbleText(textView, textMessage, rawText)
        textView.maxLines = effectiveMaxLines + 1
        textView.ellipsize = null

        if (!isConfidential) {
            TextTruncationUtil.setupDoubleClickPreview(textView, rawText, textMessage.mentions, textMessage)
        }

        textView.doOnPreDraw {
            TextTruncationUtil.applyTruncation(
                context = textView.context,
                textView = textView,
                messageId = messageId,
                maxLines = effectiveMaxLines,
                enableInteraction = !isConfidential
            ) {
                TextTruncationUtil.showFullTextDialog(
                    textView,
                    rawText,
                    textMessage.mentions,
                    textMessage
                )
            }
        }

        if (isConfidential) {
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




object ImageContentBinder : ContentBinder {
    override fun rebindForWidth(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        bindCaption(contentFrame.findViewById(R.id.textView), message as TextChatMessage)
    }

    
    private fun bindCaption(textView: TextView, textMessage: TextChatMessage) {
        if (!TextUtils.isEmpty(textMessage.message)) {
            textView.visibility = View.VISIBLE

            textView.autoLinkMask = 0

            val isConfidential = textMessage.isConfidential()
            val messageId = textMessage.id
            val rawText = textMessage.message.toString()
            val effectiveMaxLines = if (isConfidential) TextTruncationUtil.CONFIDENTIAL_MAX_LINES else TextTruncationUtil.DEFAULT_MAX_LINES

            textView.setTag(R.id.tag_truncation_message_id, messageId)
            bindBubbleText(textView, textMessage, rawText)
            textView.maxLines = effectiveMaxLines + 1
            textView.ellipsize = null

            if (!isConfidential) {
                TextTruncationUtil.setupDoubleClickPreview(textView, rawText, textMessage.mentions, textMessage)
            }

            textView.doOnPreDraw {
                TextTruncationUtil.applyTruncation(
                    context = textView.context,
                    textView = textView,
                    messageId = messageId,
                    maxLines = effectiveMaxLines,
                    enableInteraction = !isConfidential
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
            textView.visibility = View.GONE
        }
    }

    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val imageMessageView = contentFrame.findViewById<ImageAndVideoMessageView>(R.id.imageMessageView)
        val textView = contentFrame.findViewById<TextView>(R.id.textView)
        val coverView = contentFrame.findViewById<View>(R.id.v_cover)

        imageMessageView.setupImageView(textMessage, shouldSaveToPhotos, containerWidth)
        bindCaption(textView, textMessage)

        if (textMessage.isConfidential()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                contentFrame.findViewById<View>(R.id.ll_content)?.applyConfidentialBlur(BLUR_RADIUS_HIGH, CONFIDENTIAL_COVER_RADIUS_DP.dp.toFloat())
                coverView.setupConfidentialCover(isBlurred = true)

            } else {
                coverView.setupConfidentialCover(isBlurred = false)

            }
        } else {
            contentFrame.findViewById<View>(R.id.ll_content)?.clearConfidentialBlur()
            coverView.visibility = View.GONE

        }
        contentFrame.findViewById<ConfidentialAttachmentStatusView>(R.id.confidential_status_scrim)?.bind(textMessage, stacked = true)
    }
}




object AudioContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val voiceMessageView = contentFrame.findViewById<VoiceMessageView>(R.id.voice_message_view)
        val coverView = contentFrame.findViewById<View>(R.id.v_cover)

        voiceMessageView.setAudioMessage(textMessage)

        
        val palette = voiceMessageView.bubblePalette()
        val unheard = textMessage.playStatus == AudioMessageManager.PLAY_STATUS_NOT_PLAY
        voiceMessageView.applyPalette(palette, highlight = palette.link.takeIf { unheard })

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
        contentFrame.findViewById<ConfidentialAttachmentStatusView>(R.id.confidential_status_scrim)?.bind(textMessage, stacked = false)
    }
}




object AttachContentBinder : ContentBinder {
    override fun rebindForWidth(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) =
        bind(contentFrame, message, contactorCache, shouldSaveToPhotos, containerWidth)

    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val attachContentView = contentFrame.findViewById<com.difft.android.chat.widget.AttachMessageView>(
            R.id.attach_content_view
        )
        val textView = contentFrame.findViewById<TextView>(R.id.textView)
        val llContent = contentFrame.findViewById<View>(R.id.ll_content)
        val coverView = contentFrame.findViewById<View>(R.id.v_cover)

        val attachment = textMessage.attachment

        
        val longTextAttachment = attachment?.takeIf { it.isLongText() }
        val isLongText = longTextAttachment != null
        var longTextPath: String? = null
        var isLongTextDownloaded = false

        if (longTextAttachment != null) {
            longTextPath = AttachmentPathResolver.fileFor(longTextAttachment)
            
            
            isLongTextDownloaded = longTextReady(textMessage, longTextPath)

            if (isLongTextDownloaded) {
                attachContentView.visibility = View.GONE
            } else {
                attachContentView.visibility = View.VISIBLE
                attachContentView.setupAttachmentView(textMessage)
            attachContentView.applyPalette(attachContentView.bubblePalette())
            }
        } else {
            attachContentView.visibility = View.VISIBLE
            attachContentView.setupAttachmentView(textMessage)
            attachContentView.applyPalette(attachContentView.bubblePalette())
        }

        
        if (!TextUtils.isEmpty(textMessage.message)) {
            textView.visibility = View.VISIBLE
            textView.autoLinkMask = 0

            val isConfidential = textMessage.isConfidential()
            val messageId = textMessage.id
            val rawText = textMessage.message.toString()
            val effectiveMaxLines = if (isConfidential) TextTruncationUtil.CONFIDENTIAL_MAX_LINES else TextTruncationUtil.DEFAULT_MAX_LINES

            textView.setTag(R.id.tag_truncation_message_id, messageId)
            bindBubbleText(textView, textMessage, rawText)
            textView.maxLines = effectiveMaxLines + 1
            textView.ellipsize = null

            if (!isConfidential) {
                TextTruncationUtil.setupDoubleClickPreview(textView, rawText, textMessage.mentions, textMessage)
            }

            textView.doOnPreDraw {
                TextTruncationUtil.applyTruncation(
                    context = textView.context,
                    textView = textView,
                    messageId = messageId,
                    maxLines = effectiveMaxLines,
                    enableInteraction = !isConfidential,
                    
                    alwaysShowReadMore = isLongText
                ) {
                    
                    
                    
                    if (longTextReady(textMessage, longTextPath)) {
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
            textView.visibility = View.GONE
        }

        
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
        
        
        val statusLayer = contentFrame.findViewById<ConfidentialAttachmentStatusView>(R.id.confidential_status_scrim)
        if (isLongText) statusLayer?.hide() else statusLayer?.bind(textMessage, stacked = false)
    }

    





    private fun longTextReady(message: TextChatMessage, longTextPath: String?): Boolean {
        if (longTextPath == null) return false
        val attachment = message.attachment ?: return false
        return EncryptedAttachmentAccess.isLongTextReady(longTextPath, attachment.size)
    }

}




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
        val palette = contentFrame.bubblePalette()
        tvName.setTextColor(palette.text)
        contentFrame.findViewById<View>(R.id.ll_content).backgroundTintList = ColorStateList.valueOf(palette.blockFill)

        
        val contactor = contactorCache.getContactor(id)
        if (contactor != null) {
            avatarView.setAvatar(contactor, letterTextSizeDp = CONTACT_CARD_AVATAR_LETTER_TEXT_SIZE_DP)
        } else {
            
            avatarView.setAvatar(
                null,
                null,
                com.difft.android.chat.contacts.data.ContactorUtil.getFirstLetter(name),
                id,
                letterTextSizeDp = CONTACT_CARD_AVATAR_LETTER_TEXT_SIZE_DP,
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




class NotifyContentBinder() : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textChatMessage = message as com.difft.android.chat.message.NotifyChatMessage
        val textViewContent = contentFrame.findViewById<TextView>(R.id.tv_content)
        val textViewAction = contentFrame.findViewById<TextView>(R.id.tv_action)

        textViewAction.visibility = View.GONE
        textViewContent.text = textChatMessage.notifyMessage?.showContent
    }
}




object ScreenShotContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val textViewContent = contentFrame.findViewById<TextView>(R.id.tv_content)
        val textViewAction = contentFrame.findViewById<TextView>(R.id.tv_action)

        textViewAction.visibility = View.GONE
        textViewContent.text = textMessage.message
    }
}





object ConfidentialPlaceholderContentBinder : ContentBinder {
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textViewContent = contentFrame.findViewById<TextView>(R.id.tv_content)
        val textViewAction = contentFrame.findViewById<TextView>(R.id.tv_action)
        textViewAction.visibility = View.GONE
        textViewContent.text = contentFrame.context.getString(R.string.chat_confidential_message_viewed)
    }
}





private const val HISTORY_PREVIEW_LINES = 3

object MultiForwardContentBinder : ContentBinder {
    @SuppressLint("ClickableViewAccessibility")
    override fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: com.difft.android.chat.MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) {
        val textMessage = message as TextChatMessage
        val forwardContext = textMessage.forwardContext ?: return
        val forwards = forwardContext.forwards ?: return

        val tvMultiTitle = contentFrame.findViewById<androidx.appcompat.widget.AppCompatTextView>(R.id.tv_multi_title)
        val rvForwardHistory = contentFrame.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_forward_history)
        val vCover = contentFrame.findViewById<View>(R.id.v_cover)

        val palette = contentFrame.bubblePalette()
        tvMultiTitle.setTextColor(palette.text)
        contentFrame.findViewById<View>(R.id.ll_content).backgroundTintList = ColorStateList.valueOf(palette.blockFill)

        
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

        
        val adapter = ForwardMessagesAdapter(contactorCache, palette.secondaryText)
        rvForwardHistory.adapter = adapter
        rvForwardHistory.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        rvForwardHistory.setOnTouchListener { view, event ->
            LinkTextUtils.findParentChatMessageItemView(view)?.onTouchEvent(event) ?: false
        }
        adapter.submitList(forwards.take(HISTORY_PREVIEW_LINES))

        if (textMessage.isConfidential()) {
            val llContent = contentFrame.findViewById<View>(R.id.ll_content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
            contentFrame.findViewById<View>(R.id.ll_content)?.clearConfidentialBlur()
            vCover.visibility = View.GONE
        }
    }
}