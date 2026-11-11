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
