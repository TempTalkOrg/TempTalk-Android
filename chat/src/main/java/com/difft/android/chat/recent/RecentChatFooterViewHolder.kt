package com.difft.android.chat.recent

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatFragmentRecentChatFooterItemBinding
import com.difft.android.base.R as BaseR

/**
 * Chat-list footer row explaining that messages and calls are end-to-end encrypted. Stateless —
 * bound to the singleton [ListItem.E2eeFooter]; the whole row is the tap target, "Learn more" is a
 * color+bold [Spannable] run only — no [android.text.style.ClickableSpan] anywhere (E11 regression
 * guard: a nested clickable span inside a clickable row swallows the row's own click).
 */
class RecentChatFooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val binding = ChatFragmentRecentChatFooterItemBinding.bind(itemView)

    fun bind(onFooterClicked: () -> Unit) {
        val context = binding.root.context
        val isLarger = TextSizeUtil.isLarger
        binding.textviewE2eeHint.textSize = if (isLarger) 18f else 12f
        binding.textviewE2eeHint.text = buildHintText(context, isLarger)
        binding.root.setOnClickListener { onFooterClicked() }
        binding.root.contentDescription = plainHintText(context)
        binding.textviewE2eeHint.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun buildHintText(context: Context, isLarger: Boolean): CharSequence {
        val iconSizePx = ((if (isLarger) 18 else 12) * context.resources.displayMetrics.density).toInt()
        val lockDrawable = ContextCompat.getDrawable(context, BaseR.drawable.base_tabler_lock)
            ?.mutate()?.apply {
                setTint(ContextCompat.getColor(context, BaseR.color.t_third))
                setBounds(0, 0, iconSizePx, iconSizePx)
            }
        return SpannableStringBuilder().apply {
            if (lockDrawable != null) {
                append(" ", ImageSpan(lockDrawable, ImageSpan.ALIGN_CENTER), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" ")
            }
            append(context.getString(R.string.chat_list_e2ee_hint))
            append(" ")
            val learnMoreStart = length
            append(context.getString(BaseR.string.e2ee_learn_more))
            setSpan(ForegroundColorSpan(ContextCompat.getColor(context, BaseR.color.t_info)), learnMoreStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), learnMoreStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun plainHintText(context: Context): String =
        "${context.getString(R.string.chat_list_e2ee_hint)} ${context.getString(BaseR.string.e2ee_learn_more)}"
}
