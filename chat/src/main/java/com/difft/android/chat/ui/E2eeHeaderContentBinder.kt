package com.difft.android.chat.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.EncryptionHeaderChatMessage

/**
 * Content binder for [EncryptionHeaderChatMessage] (VIEW_TYPE_E2EE_HEADER). Whole-row click is
 * wired in [ChatMessageViewHolder.Notify.bind] — no ClickableSpan here (project has a
 * LinkMovementMethod × scroll conflict precedent, TextTruncationUtil.kt:66-128).
 */
object E2eeHeaderContentBinder : ContentBinder {
    override fun bind(
        contentFrame: ViewGroup,
        message: ChatMessage,
        contactorCache: MessageContactsCacheUtil,
        shouldSaveToPhotos: Boolean,
        containerWidth: Int,
    ) {
        val header = message as EncryptionHeaderChatMessage
        val tvContent = contentFrame.findViewById<AppCompatTextView>(R.id.tv_e2ee_header_content)
        val context = contentFrame.context

        // Lock icon is embedded as a leading ImageSpan (emoji-style), not a compound drawable —
        // setCompoundDrawablesRelative vertical-centers the icon across the WHOLE (possibly
        // multi-line) TextView instead of pinning it to the first line. Same technique as
        // RecentChatFooterViewHolder.buildHintText.
        val iconSizePx = (12 * context.resources.displayMetrics.density).toInt()
        val lockDrawable = ContextCompat.getDrawable(context, com.difft.android.base.R.drawable.base_tabler_lock)
            ?.mutate()
            ?.apply {
                setTint(ContextCompat.getColor(context, com.difft.android.base.R.color.t_third))
                setBounds(0, 0, iconSizePx, iconSizePx)
            }

        val builder = SpannableStringBuilder()
        if (lockDrawable != null) {
            builder.append(" ", ImageSpan(lockDrawable, ImageSpan.ALIGN_CENTER), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(" ")
        }

        // Non-friend variant uses the legacy chat_privacy_banner copy verbatim (minus its leading
        // lock emoji, now rendered via the leading ImageSpan above); only the base copy differs —
        // the blue "Learn more" suffix below is shared by both variants.
        val baseCopyRes = if (header.isNonFriendVariant) R.string.chat_e2ee_header_non_friend else R.string.chat_e2ee_header_hint
        builder.append(context.getString(baseCopyRes))
        builder.append(" ")
        val learnMoreStart = builder.length
        builder.append(context.getString(com.difft.android.base.R.string.e2ee_learn_more))
        builder.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, com.difft.android.base.R.color.t_info)),
            learnMoreStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            learnMoreStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        tvContent.text = builder
    }
}
