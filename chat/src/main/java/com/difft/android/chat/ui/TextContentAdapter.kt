package com.difft.android.chat.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isConfidential


open class TextContentAdapter : MessageContentAdapter() {
    class TextContentViewHolder(val rootView: View) : ContentViewHolder()

    override val layoutRes: Int = R.layout.chat_item_content_text

    override fun onBindContentView(message: ChatMessage, viewHolder: ContentViewHolder) {
        message as TextChatMessage
        val textContentViewHolder = viewHolder as TextContentViewHolder
//        textContentViewHolder.textView.text = textChatMessage.message
//        textContentViewHolder.textView.autoLinkMask = Linkify.ALL
//        textContentViewHolder.textView.movementMethod = LinkMovementMethod.getInstance()

        val textView = textContentViewHolder.rootView.findViewById<AppCompatTextView>(R.id.textView)

        textView.setOnClickListener {
            LinkTextUtils.findParentChatMessageItemView(textView)?.performClick()
        }
        textView.setOnLongClickListener {
            LinkTextUtils.findParentChatMessageItemView(textView)?.performLongClick()
            true
        }

        textView.autoLinkMask = 0
        textView.movementMethod = null

        if (TextSizeUtil.isLager()) {
            textView.textSize = 24f
        } else {
            textView.textSize = 16f
        }

        val content = message.message.toString()

        if (message.isConfidential()) {
            val spannableText = getGrayBlockTextWithTransparentForeground(
                content, ContextCompat.getColor(
                    textView.context,
                    com.difft.android.base.R.color.bg_confidential
                ),
                textView.width,
                textView.paint
            )
            textView.text = spannableText
            textView.maxLines = 5
        } else {
            LinkTextUtils.setMarkdownToTextview(textContentViewHolder.rootView.context, message.message.toString(), textView, message.mentions)
            textView.maxLines = Int.MAX_VALUE
        }
    }

    override fun createViewHolder(viewGroup: ViewGroup): ContentViewHolder {
        return TextContentViewHolder(viewGroup.findViewById(R.id.cl_text_content_root))
    }


//    private fun getGrayBlockTextWithTransparentForeground(
//        originalText: String,
//        blockColor: Int = Color.GRAY
//    ): SpannableStringBuilder {
//        val spannableStringBuilder = SpannableStringBuilder(originalText)
//
//        var i = 0
//        while (i < originalText.length) {
//            if (originalText[i] != ' ') {
//                val start = i
//                var end = i
//                while (end < originalText.length && originalText[end] != ' ') {
//                    end++
//                }
//
//                // 设置背景色（灰色方块）
//                spannableStringBuilder.setSpan(
////                    BackgroundColorSpan(blockColor),
//                    UniformBackgroundSpan(blockColor, 28.dp),
//                    start, end,
//                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
//                )
//
////                // 设置前景色（全透明隐藏文字）
////                spannableStringBuilder.setSpan(
////                    ForegroundColorSpan(Color.TRANSPARENT),
////                    start, end,
////                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
////                )
//
//                i = end
//            } else {
//                i++
//            }
//        }
//
//        return spannableStringBuilder
//    }

//    class UniformBackgroundSpan(
//        private val backgroundColor: Int,
//        private val blockHeight: Int
//    ) : ReplacementSpan() {
//
//        override fun getSize(
//            paint: Paint,
//            text: CharSequence,
//            start: Int,
//            end: Int,
//            fm: Paint.FontMetricsInt?
//        ): Int {
//            // 计算整个文本的宽度
//            return paint.measureText(text, start, end).toInt()
//        }
//
//        override fun draw(
//            canvas: Canvas,
//            text: CharSequence,
//            start: Int,
//            end: Int,
//            x: Float,
//            top: Int,
//            y: Int,
//            bottom: Int,
//            paint: Paint
//        ) {
//            val originalColor = paint.color
//            val availableWidth = canvas.width.toFloat() // TextView 的宽度
//
//            var currentX = x
//            var currentY = y.toFloat()
//
//            var currentIndex = start
//            while (currentIndex < end) {
//                // 获取当前行可以显示的字符数量
//                val count = paint.breakText(text, currentIndex, end, true, availableWidth - currentX, null)
//                val lineText = text.subSequence(currentIndex, currentIndex + count).toString()
//
//                // 绘制背景
//                val rectTop = currentY + paint.fontMetrics.top
//                val rectBottom = currentY + paint.fontMetrics.bottom
//                paint.color = backgroundColor
//                canvas.drawRect(currentX, rectTop, currentX + paint.measureText(lineText), rectBottom, paint)
//
//                // 绘制透明文字
//                paint.color = Color.TRANSPARENT
//                canvas.drawText(lineText, currentX, currentY, paint)
//
//                // 更新绘制位置
//                currentIndex += count
//                currentY += blockHeight
//                currentX = x // 新的一行从左边开始
//            }
//
//            // 恢复原来的颜色
//            paint.color = originalColor
//        }
//    }

    private fun getGrayBlockTextWithTransparentForeground(
        originalText: String,
        blockColor: Int,
        textViewWidth: Int,
        paint: Paint
    ): SpannableStringBuilder {
        val spannableStringBuilder = SpannableStringBuilder(originalText)

        var start = 0
        while (start < originalText.length) {
            // 找到段落结束位置（以空格为分割基础）
            var end = start
            while (end < originalText.length && originalText[end] != ' ') {
                end++
            }

            // 如果当前段落超出宽度，则进一步分割
            while (paint.measureText(originalText, start, end) > textViewWidth) {
                var splitEnd = start
                while (splitEnd < end && paint.measureText(originalText, start, splitEnd) <= textViewWidth) {
                    splitEnd++
                }
                // 添加有效的 Span
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

            // 添加剩余的 Span
            if (end > start) {
                spannableStringBuilder.setSpan(
                    UniformBackgroundSpan(blockColor, 26.dp),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // 跳过空格
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

            // 计算背景矩形的上下边界
            val rectTop = y + paint.fontMetrics.top
            val rectBottom = y + paint.fontMetrics.bottom

            // 绘制背景
            paint.color = backgroundColor
            canvas.drawRect(
                x, rectTop, x + paint.measureText(text, start, end), rectBottom, paint
            )

            // 绘制透明文字
            paint.color = Color.TRANSPARENT
            canvas.drawText(text, start, end, x, y.toFloat(), paint)

            // 恢复原来的画笔颜色
            paint.color = originalColor
        }
    }
}