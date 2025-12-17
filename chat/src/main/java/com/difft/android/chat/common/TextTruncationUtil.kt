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
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.chat.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文本截断工具类
 *
 * 用于处理超长文本的截断显示，并在末尾添加 "Read more" 可点击链接
 */
object TextTruncationUtil {

    const val DEFAULT_MAX_LINES = 20

    /**
     * 应用文本截断逻辑
     *
     * 使用 lineCount > maxLines 判断是否需要截断，
     * 截取已渲染的 SpannableStringBuilder（保留原有格式），
     * 在末尾添加换行和 "Read more" 可点击链接
     *
     * @param context Context
     * @param textView 目标 TextView
     * @param messageId 消息 ID，用于检查 View 是否被复用
     * @param maxLines 最大行数，默认 20 行
     * @param onReadMoreClick 点击 "Read more" 时的回调
     */
    fun applyTruncation(
        context: Context,
        textView: TextView,
        messageId: String,
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
        if (lineCount > maxLines) {
            // 获取第 maxLines 行的末尾位置，截取文本到这个位置
            val truncateAtLineEnd = layout.getLineEnd(maxLines - 1)
            val currentText = textView.text
            val truncatedText = currentText.subSequence(0, truncateAtLineEnd)

            // 添加 "Read more" 可点击链接（不添加 "..."）
            val readMoreText = context.getString(R.string.SpanUtil__read_more)
            val builder = SpannableStringBuilder(truncatedText)

            // 修复：防止边界处的 ClickableSpan 扩展到 "Read more" 区域
            // 获取所有在截断边界处结束的 ClickableSpan，重新设置为 EXCLUSIVE 模式
            val truncatedLength = builder.length
            val clickableSpans = builder.getSpans(0, truncatedLength, ClickableSpan::class.java)
            for (span in clickableSpans) {
                val spanStart = builder.getSpanStart(span)
                val spanEnd = builder.getSpanEnd(span)
                // 如果 span 在边界处结束，需要重新设置以防止扩展
                // 添加防御性检查确保索引有效
                if (spanEnd == truncatedLength && spanStart >= 0 && spanStart <= spanEnd) {
                    builder.removeSpan(span)
                    builder.setSpan(span, spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

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

    /**
     * 异步读取文件内容并显示完整文本对话框
     * 适用于CONTENT_TYPE_LONG_TEXT类型的附件，文件可能较大（最大10MB）
     *
     * @param view 触发显示的 View
     * @param filePath 文件路径
     * @param fallbackText 如果文件读取失败，显示的备用文本
     * @param mentions Mention 列表
     */
    fun showFullTextDialogFromFile(
        view: View,
        filePath: String?,
        fallbackText: String,
        mentions: List<difft.android.messageserialization.model.Mention>? = null
    ) {
        if (filePath.isNullOrEmpty()) {
            showFullTextDialog(view, fallbackText, mentions)
            return
        }

        appScope.launch {
            val content = withContext(Dispatchers.IO) {
                readTextFileContent(filePath)
            }
            withContext(Dispatchers.Main) {
                showFullTextDialog(view, content.ifEmpty { fallbackText }, mentions)
            }
        }
    }

    /**
     * 读取文本文件内容
     */
    private fun readTextFileContent(filePath: String): String {
        return try {
            File(filePath).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            L.e(e) { "[LongText] Failed to read text file: $filePath" }
            ""
        }
    }
}