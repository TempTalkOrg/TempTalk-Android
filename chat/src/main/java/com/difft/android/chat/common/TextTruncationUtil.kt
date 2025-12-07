package com.difft.android.chat.common

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.chat.R

/**
 * 文本截断工具类
 *
 * 用于处理超长文本的截断显示，并在末尾添加 "Read more" 可点击链接
 */
object TextTruncationUtil {

    const val DEFAULT_MAX_LINES = 20

    /**
     * 应用文本截断逻辑（带 markdown 格式的版本）
     *
     * @param context Context
     * @param textView 目标 TextView
     * @param messageId 消息 ID，用于检查 View 是否被复用
     * @param rawText 原始 markdown 文本（未格式化）
     * @param mentions Mention 列表
     * @param maxLines 最大行数，默认 20 行
     * @param onReadMoreClick 点击 "Read more" 时的回调
     */
    fun applyTruncationWithMarkdown(
        context: Context,
        textView: TextView,
        messageId: String,
        rawText: String,
        mentions: List<difft.android.messageserialization.model.Mention>?,
        maxLines: Int = DEFAULT_MAX_LINES,
        onReadMoreClick: () -> Unit
    ) {
        // 使用 messageId 作为标识，检查当前 TextView 显示的是否是这条消息
        // 如果不是，说明 View 已经被复用，不应该执行截断
        val currentMessageId = textView.getTag(R.id.tag_truncation_message_id) as? String
        if (currentMessageId != messageId) {
            // View 已被复用到其他消息，忽略此次截断
            return
        }

        // 获取当前行数（基于 maxLines = 21 的限制）
        val layout = textView.layout ?: return
        val lineCount = layout.lineCount

        // 如果行数 > 20，说明文本超过了 20 行，需要截断
        // 不需要知道真实行数是 21 还是 100，因为都要截断到 20 行
        if (lineCount > maxLines) {
            // 简化逻辑：直接截取到第 maxLines 行末尾，然后替换末尾字符为 "..."
            // 不需要精确计算宽度，直接截取并替换，避免复杂计算导致的问题
            val truncateAtLineEnd = layout.getLineEnd(maxLines - 1)

            // 获取原始文本
            val originalText = textView.text

            // 计算原始文本和渲染文本的长度比例，用于估算 raw text 的截断位置
            val lengthRatio = if (originalText.isNotEmpty()) {
                rawText.length.toDouble() / originalText.length
            } else {
                1.0
            }

            // 估算 raw text 的截断位置，只需留出 "..." 的长度
            val estimatedRawIndex = (truncateAtLineEnd * lengthRatio).toInt()
            val truncateAtIndex = (estimatedRawIndex - 3).coerceIn(0, rawText.length)  // 只减去 "..." 的3个字符

            // 截断原始 markdown 文本并添加 "..."
            val truncatedRawText = rawText.substring(0, truncateAtIndex).trimEnd() + "..."

            // 重新渲染截断后的 markdown 文本
            LinkTextUtils.setMarkdownToTextview(context, truncatedRawText, textView, mentions)

            // 添加 "Read more" 可点击链接
            val currentText = textView.text
            val readMoreText = context.getString(R.string.SpanUtil__read_more)
            val builder = SpannableStringBuilder(currentText)
            builder.append("\n")

            val readMoreStart = builder.length
            builder.append(readMoreText)

            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onReadMoreClick()
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = ContextCompat.getColor(context, com.difft.android.base.R.color.t_info)
                    ds.isUnderlineText = false
                }
            }

            builder.setSpan(
                clickableSpan,
                readMoreStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            textView.text = builder
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.maxLines = maxLines + 1  // +1 for the "Read more" line
        } else {
            // No truncation needed, keep the formatted text as is
            textView.maxLines = Int.MAX_VALUE
        }
    }

    /**
     * 显示完整文本对话框
     *
     * @param view 触发显示的 View
     * @param fullText 完整文本内容
     * @param mentions Mention 列表
     */
    fun showFullTextDialog(
        view: View,
        fullText: String,
        mentions: List<difft.android.messageserialization.model.Mention>? = null
    ) {
        // Try to get FragmentActivity from view's context
        var context = view.context
        while (context is android.content.ContextWrapper) {
            if (context is androidx.fragment.app.FragmentActivity) {
                val fragment = com.difft.android.chat.ui.ChatMessageListFragment.TextContentBottomSheetFragment.newInstance(
                    fullText,
                    mentions
                )
                fragment.show(context.supportFragmentManager, "TextContentBottomSheet")
                return
            }
            context = context.baseContext
        }
    }
}