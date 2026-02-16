package com.difft.android.chat.common

import android.annotation.SuppressLint
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.chat.R
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.buildForwardData
import com.difft.android.chat.ui.textpreview.TextPreviewActivity
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
     * 为 TextView 设置双击打开文本预览的监听
     *
     * 双击打开 TextPreviewActivity，单击传递给父容器，长按传递给父容器
     *
     * @param textView 目标 TextView
     * @param fullText 完整文本内容
     * @param mentions Mention 列表
     * @param sourceMessage 源消息，用于全选转发时构建转发上下文（延迟到打开时才构建）
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setupDoubleClickPreview(
        textView: TextView,
        fullText: String,
        mentions: List<difft.android.messageserialization.model.Mention>? = null,
        sourceMessage: TextChatMessage? = null
    ) {
        // 标志：长按是否已触发，用于阻止松开时触发链接点击
        var longPressTriggered = false

        val gestureDetector = GestureDetector(textView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击：打开文本预览页面（此时才构建 ForwardContext）
                val forwardContext = sourceMessage?.buildForwardData()?.second
                TextPreviewActivity.start(textView.context, fullText, mentions, forwardContext)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 单击确认后（等待双击超时）：如果不是点击链接，传递给父容器
                // 注意：如果点击了 ClickableSpan，在 ACTION_UP 时已经由 LinkMovementMethod 处理了
                if (!isTouchOnClickableSpan(textView, e)) {
                    LinkTextUtils.findParentChatMessageItemView(textView)?.performClick()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 长按：设置标志并传递给父容器
                longPressTriggered = true
                LinkTextUtils.findParentChatMessageItemView(textView)?.performLongClick()
            }
        })

        textView.setOnTouchListener { _, event ->
            // ACTION_DOWN 时重置长按标志
            if (event.action == MotionEvent.ACTION_DOWN) {
                longPressTriggered = false
            }

            gestureDetector.onTouchEvent(event)

            // 判断是否应该让 LinkMovementMethod 处理事件：
            // 1. 触摸点在 ClickableSpan 上
            // 2. 长按未触发（避免长按链接后松开时触发链接点击）
            // 3. 是 ACTION_UP 事件（只在松开时让链接响应，其他事件由 GestureDetector 处理）
            val onClickableSpan = isTouchOnClickableSpan(textView, event)
            val shouldPassToLinkMovement = onClickableSpan &&
                    !longPressTriggered &&
                    event.action == MotionEvent.ACTION_UP

            !shouldPassToLinkMovement
        }
    }

    /**
     * 检查触摸点是否在 ClickableSpan（超链接或 "Read more"）上
     */
    private fun isTouchOnClickableSpan(textView: TextView, event: MotionEvent): Boolean {
        val layout = textView.layout ?: return false
        val text = textView.text as? Spanned ?: return false

        // 计算触摸点对应的文本偏移量
        val x = (event.x - textView.totalPaddingLeft + textView.scrollX).toInt()
        val y = (event.y - textView.totalPaddingTop + textView.scrollY).toInt()

        // 边界检查
        if (x < 0 || y < 0) return false

        val line = layout.getLineForVertical(y)
        if (line < 0 || line >= layout.lineCount) return false

        val lineLeft = layout.getLineLeft(line)
        val lineRight = layout.getLineRight(line)

        // 检查 x 是否在该行文本范围内
        if (x < lineLeft || x > lineRight) return false

        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        if (offset < 0 || offset >= text.length) return false

        // 检查该位置是否有 ClickableSpan
        val clickableSpans = text.getSpans(offset, offset, ClickableSpan::class.java)
        return clickableSpans.isNotEmpty()
    }

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
        val currentText = textView.text

        // 判断是否需要截断：
        // 1. 行数 > maxLines（明确超过限制）
        // 2. 或者行数 >= maxLines 且文本内容超出了第 maxLines 行的末尾位置
        //    （处理边界情况：文本刚好填满 maxLines 行但后面还有内容被截断）
        val needsTruncation = if (lineCount >= maxLines) {
            val endOfMaxLine = layout.getLineEnd(maxLines - 1)
            endOfMaxLine < currentText.length
        } else {
            false
        }

        if (needsTruncation) {
            // 获取第 maxLines 行的末尾位置，截取文本到这个位置
            val truncateAtLineEnd = layout.getLineEnd(maxLines - 1)
            val truncatedText = currentText.subSequence(0, truncateAtLineEnd)

            // 添加 "Read more" 可点击链接（不添加 "..."）
            val readMoreText = context.getString(R.string.SpanUtil__read_more)
            val builder = SpannableStringBuilder(truncatedText)

            // 移除末尾的空白字符和换行符，避免 "Read more" 出现在错误的行
            // 例如：如果截断文本以 \n 结尾，再添加 \n 会导致 "Read more" 在第22行而被截断
            while (builder.isNotEmpty() && builder[builder.length - 1].isWhitespace()) {
                builder.delete(builder.length - 1, builder.length)
            }

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
     * 显示完整文本预览页面
     *
     * @param view 触发显示的 View
     * @param fullText 完整文本内容
     * @param mentions Mention 列表
     * @param sourceMessage 源消息，用于全选转发时构建转发上下文（延迟到打开时才构建）
     */
    fun showFullTextDialog(
        view: View,
        fullText: String,
        mentions: List<difft.android.messageserialization.model.Mention>? = null,
        sourceMessage: TextChatMessage? = null
    ) {
        // 此时才构建 ForwardContext
        val forwardContext = sourceMessage?.buildForwardData()?.second
        TextPreviewActivity.start(view.context, fullText, mentions, forwardContext)
    }

    /**
     * 异步读取文件内容并显示完整文本对话框
     * 适用于CONTENT_TYPE_LONG_TEXT类型的附件，文件可能较大（最大10MB）
     *
     * @param view 触发显示的 View
     * @param filePath 文件路径
     * @param fallbackText 如果文件读取失败，显示的备用文本
     * @param mentions Mention 列表
     * @param sourceMessage 源消息，用于全选转发时构建转发上下文（延迟到打开时才构建）
     */
    fun showFullTextDialogFromFile(
        view: View,
        filePath: String?,
        fallbackText: String,
        mentions: List<difft.android.messageserialization.model.Mention>? = null,
        sourceMessage: TextChatMessage? = null
    ) {
        if (filePath.isNullOrEmpty()) {
            showFullTextDialog(view, fallbackText, mentions, sourceMessage)
            return
        }

        appScope.launch {
            val content = withContext(Dispatchers.IO) {
                readTextFileContent(filePath)
            }
            withContext(Dispatchers.Main) {
                showFullTextDialog(view, content.ifEmpty { fallbackText }, mentions, sourceMessage)
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
            L.e(e) { "[LongText] Failed to read text file: ${e.message}" }
            ""
        }
    }
}