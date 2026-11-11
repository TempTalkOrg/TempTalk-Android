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
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.buildForwardData
import com.difft.android.chat.ui.textpreview.TextPreviewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext




object TextTruncationUtil {

    const val DEFAULT_MAX_LINES = 20
    const val CONFIDENTIAL_MAX_LINES = 8

    






    @SuppressLint("ClickableViewAccessibility")
    fun setupDoubleClickPreview(
        textView: TextView,
        fullText: String,
        mentions: List<difft.android.messageserialization.model.Mention>? = null,
        sourceMessage: TextChatMessage? = null
    ) {
        
        var longPressTriggered = false

        val gestureDetector = GestureDetector(textView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                
                val forwardContext = sourceMessage?.buildForwardData()?.second
                TextPreviewActivity.start(textView.context, fullText, mentions, forwardContext, sourceMessage)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                
                
                if (!isTouchOnClickableSpan(textView, e)) {
                    LinkTextUtils.findParentChatMessageItemView(textView)?.performClick()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                longPressTriggered = true
                LinkTextUtils.findParentChatMessageItemView(textView)?.performLongClick()
            }
        })

        textView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                longPressTriggered = false
            }

            gestureDetector.onTouchEvent(event)

            
            
            val onClickableSpan = isTouchOnClickableSpan(textView, event)
            val shouldPassToLinkMovement = onClickableSpan &&
                    !longPressTriggered &&
                    event.action == MotionEvent.ACTION_UP

            !shouldPassToLinkMovement
        }
    }

    private fun isTouchOnClickableSpan(textView: TextView, event: MotionEvent): Boolean {
        val layout = textView.layout ?: return false
        val text = textView.text as? Spanned ?: return false

        val x = (event.x - textView.totalPaddingLeft + textView.scrollX).toInt()
        val y = (event.y - textView.totalPaddingTop + textView.scrollY).toInt()

        if (x < 0 || y < 0) return false

        val line = layout.getLineForVertical(y)
        if (line < 0 || line >= layout.lineCount) return false

        val lineLeft = layout.getLineLeft(line)
        val lineRight = layout.getLineRight(line)

        if (x < lineLeft || x > lineRight) return false

        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        if (offset < 0 || offset >= text.length) return false

        val clickableSpans = text.getSpans(offset, offset, ClickableSpan::class.java)
        return clickableSpans.isNotEmpty()
    }

    







    fun applyTruncation(
        context: Context,
        textView: TextView,
        messageId: String,
        maxLines: Int = DEFAULT_MAX_LINES,
        enableInteraction: Boolean = true,
        alwaysShowReadMore: Boolean = false,
        onReadMoreClick: () -> Unit
    ) {
        
        val currentMessageId = textView.getTag(R.id.tag_truncation_message_id) as? String
        if (currentMessageId != messageId) {
            return
        }

        val layout = textView.layout ?: return
        val lineCount = layout.lineCount
        val currentText = textView.text

        
        val isRealTruncation = lineCount >= maxLines && layout.getLineEnd(maxLines - 1) < currentText.length
        val needsTruncation = isRealTruncation || alwaysShowReadMore

        if (needsTruncation) {
            val truncateAtLineEnd = if (isRealTruncation) layout.getLineEnd(maxLines - 1) else currentText.length
            val truncatedText = currentText.subSequence(0, truncateAtLineEnd)

            val readMoreText = context.getString(R.string.SpanUtil__read_more)
            val builder = SpannableStringBuilder(truncatedText)

            
            while (builder.isNotEmpty() && builder[builder.length - 1].isWhitespace()) {
                builder.delete(builder.length - 1, builder.length)
            }

            
            val truncatedLength = builder.length
            val clickableSpans = builder.getSpans(0, truncatedLength, ClickableSpan::class.java)
            for (span in clickableSpans) {
                val spanStart = builder.getSpanStart(span)
                val spanEnd = builder.getSpanEnd(span)
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
                    
                    ds.color = textView.linkTextColors?.defaultColor
                        ?: ContextCompat.getColor(context, com.difft.android.base.R.color.t_info)
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
            if (enableInteraction) {
                textView.movementMethod = LinkMovementMethod.getInstance()
            }
            textView.maxLines = maxLines + 1  
        } else {
            
            textView.maxLines = Int.MAX_VALUE
        }
    }

    






    fun showFullTextDialog(
        view: View,
        fullText: String,
        mentions: List<difft.android.messageserialization.model.Mention>? = null,
        sourceMessage: TextChatMessage? = null
    ) {
        
        val forwardContext = sourceMessage?.buildForwardData()?.second
        TextPreviewActivity.start(view.context, fullText, mentions, forwardContext, sourceMessage)
    }

    







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

        val context = view.context.applicationContext
        appScope.launch {
            val content = withContext(Dispatchers.IO) {
                readTextFileContent(context, filePath)
            }
            withContext(Dispatchers.Main) {
                showFullTextDialog(view, content.ifEmpty { fallbackText }, mentions, sourceMessage)
            }
        }
    }

    


    private fun readTextFileContent(context: Context, filePath: String): String {
        return try {
            EncryptedAttachmentAccess.readDecryptedText(context, filePath) ?: ""
        } catch (e: Exception) {
            L.e(e) { "[LongText] Failed to read text file: ${e.message}" }
            ""
        }
    }
}