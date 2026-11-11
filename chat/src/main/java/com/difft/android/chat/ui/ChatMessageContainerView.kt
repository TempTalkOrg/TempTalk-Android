package com.difft.android.chat.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.difft.android.chat.R
import com.difft.android.chat.ui.theme.AnchoredGradientBubbleDrawable
import com.difft.android.chat.ui.theme.ChatBubbleGradient
import com.difft.android.chat.ui.theme.ChatTheme
import com.difft.android.chat.widget.chatContainerWidthPx

class ChatMessageContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    
    var sent = false

    
    var chatTheme = ChatTheme.CLASSIC
        private set

    private var classicBackground: Drawable? = null
    private var themedBackground: Drawable? = null
    private var themedBackgroundKey: Any? = null

    




    fun applyChatTheme(theme: ChatTheme, gradient: ChatBubbleGradient?) {
        if (classicBackground == null) classicBackground = background
        chatTheme = theme
        if (theme == ChatTheme.CLASSIC) {
            background = classicBackground
            return
        }
        val key = gradient ?: theme
        if (themedBackground == null || themedBackgroundKey !== key) {
            themedBackground = if (gradient != null) {
                AnchoredGradientBubbleDrawable(gradient, this, sent)
            } else {
                bubbleFillDrawable(context, sent, theme)
            }
            themedBackgroundKey = key
        }
        background = themedBackground
    }

    



    var avatarColumn = false

    



    var growsWithWindow = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    
    var capsWidth = true
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    




    var masked = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = masked || super.onInterceptTouchEvent(ev)

    override fun onMeasure(rawWidthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMeasureSpec = cappedWidthSpec(rawWidthMeasureSpec)
        fitCardToBubble(MeasureSpec.getSize(widthMeasureSpec))
        fitQuoteToBubble(MeasureSpec.getSize(widthMeasureSpec))
        
        resetAllPaddingsToDefault()

        
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        
        
        val timeWrapper = findViewById<View>(R.id.ll_time_wrapper)
        if (timeWrapper != null && timeWrapper.visibility == VISIBLE) {
            val clMessageTime = findViewById<View>(R.id.cl_message_time)
            val timeViewWidth = if (clMessageTime != null && clMessageTime.isVisible) clMessageTime.measuredWidth else 0
            
            val wrapperParams = timeWrapper.layoutParams as? MarginLayoutParams
            if (timeViewWidth == 0 && wrapperParams != null && wrapperParams.bottomMargin != 0) {
                wrapperParams.bottomMargin = 0
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }

            if (timeViewWidth > 0) {
                
                val effectiveMaxWidth = calculateEffectiveMaxWidth()

                
                var needsExtraBottomSpace = false
                var needsRemeasure = false
                var belowCard = false

                
                val reactionsView = findViewById<View>(R.id.reactions_view)
                if (reactionsView?.visibility == VISIBLE && reactionsView is FlowLayout) {
                    needsExtraBottomSpace = shouldPlaceTimeBelowReactions(reactionsView, timeViewWidth, effectiveMaxWidth)
                    needsRemeasure = adjustReactionsPadding(reactionsView, needsExtraBottomSpace, timeViewWidth)
                } else if (findViewWithTag<View>(TIME_BELOW_TAG) != null) {
                    
                    needsExtraBottomSpace = true
                    belowCard = true
                } else {
                    
                    val contentFrame = findViewById<View>(R.id.contentFrame)
                    if (contentFrame?.visibility == VISIBLE) {
                        val textView = contentFrame.findViewById<TextView>(R.id.textView)
                        textView?.let { tv ->
                            needsExtraBottomSpace = shouldPlaceTimeBelowText(tv, timeViewWidth, effectiveMaxWidth)
                            needsRemeasure = adjustTextPadding(tv, needsExtraBottomSpace, timeViewWidth)
                        }
                    }
                }

                
                val timeParams = timeWrapper.layoutParams as? MarginLayoutParams
                timeParams?.let { params ->
                    val newMarginTop = if (needsExtraBottomSpace) 0 else (-26).dp
                    
                    
                    val newMarginBottom = if (belowCard) CARD_TIME_BOTTOM_DP.dp else 0
                    if (params.topMargin != newMarginTop || params.bottomMargin != newMarginBottom) {
                        params.topMargin = newMarginTop
                        params.bottomMargin = newMarginBottom
                        
                        
                        
                        needsRemeasure = true
                    }
                }

                
                if (needsRemeasure) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }
        }

        
        
        
        
        alignQuoteThumbnailToTrailing()
    }

    







    private fun alignQuoteThumbnailToTrailing() {
        val quoteThumbnail = findViewById<View>(R.id.quoteThumbnail) ?: return
        if (quoteThumbnail.visibility != VISIBLE) return
        val quoteZone = findViewById<View>(R.id.quoteZone) ?: return
        if (quoteZone.visibility != VISIBLE) return
        val lp = quoteZone.layoutParams as? MarginLayoutParams ?: return
        val target = measuredWidth - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin
        if (target > quoteZone.measuredWidth) {
            quoteZone.measure(
                MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(quoteZone.measuredHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    
    private var cardLayoutWidth = 0

    



    private fun fitCardToBubble(width: Int) {
        val card = findViewWithTag<View>(TIME_BELOW_TAG) ?: return
        val params = card.layoutParams ?: return
        
        if (cardLayoutWidth == 0 && params.width > 0) cardLayoutWidth = params.width
        val cardWidth = if (cardLayoutWidth > 0) minOf(width, cardLayoutWidth) else width
        
        if (cardWidth > 0 && params.width != cardWidth) params.width = cardWidth
    }

    




    private fun fitQuoteToBubble(bubbleWidth: Int) {
        val zone = findViewById<View>(R.id.quoteZone) ?: return
        if (zone.visibility != VISIBLE || bubbleWidth <= 0) return
        val thumbnail = findViewById<View>(R.id.quoteThumbnail)
        
        
        val chromeDp = if (thumbnail?.visibility == VISIBLE) QUOTE_CHROME_WITH_THUMBNAIL_DP else QUOTE_CHROME_DP
        
        val maxWidth = (minOf(bubbleWidth, maxBubblePx()) - chromeDp.dp).coerceAtLeast(QUOTE_TEXT_MIN_DP.dp)
        findViewById<TextView>(R.id.quoteAuthor)?.let { if (it.maxWidth != maxWidth) it.maxWidth = maxWidth }
        findViewById<TextView>(R.id.quoteText)?.let { if (it.maxWidth != maxWidth) it.maxWidth = maxWidth }
    }

    
    private fun maxBubblePx(): Int =
        if (growsWithWindow) BubbleWidth.maxBubblePx(bubbleColumnPx(), resources.displayMetrics.density) else BubbleWidth.MIN_DP.dp

    private fun cappedWidthSpec(spec: Int): Int {
        if (!capsWidth) return spec
        val cap = maxBubblePx()
        return when (MeasureSpec.getMode(spec)) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
            else -> if (MeasureSpec.getSize(spec) > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST) else spec
        }
    }

    private fun shouldPlaceTimeBelowReactions(reactionsView: FlowLayout, timeViewWidth: Int, effectiveMaxWidth: Int): Boolean {
        val lastLineWidth = reactionsView.getLastLineWidth()
        val totalWidth = lastLineWidth + timeViewWidth
        
        
        if (reactionsView.getLineCount() > 1) {
            val rowsWidth = reactionsView.measuredWidth - reactionsView.paddingStart - reactionsView.paddingEnd
            return totalWidth > minOf(rowsWidth, effectiveMaxWidth)
        }
        return totalWidth > effectiveMaxWidth
    }

    private fun shouldPlaceTimeBelowText(textView: TextView, timeViewWidth: Int, effectiveMaxWidth: Int): Boolean {
        textView.layout?.let { layout ->
            if (layout.lineCount > 0) {
                
                val lastLineIndex = layout.lineCount - 1
                val lastLineWidth = layout.getLineRight(lastLineIndex) - layout.getLineLeft(lastLineIndex)

                
                
                val totalWidth = textView.paddingStart + lastLineWidth + timeViewWidth
                val result = totalWidth > effectiveMaxWidth

                return result
            }
        }
        return false
    }

    private fun adjustReactionsPadding(reactionsView: FlowLayout, needsExtraBottomSpace: Boolean, timeViewWidth: Int): Boolean {
        
        return if (!needsExtraBottomSpace && reactionsView.getLineCount() == 1) {
            
            val neededPadding = timeViewWidth + 3.dp
            if (reactionsView.paddingEnd != neededPadding) {
                reactionsView.setPaddingRelative(
                    reactionsView.paddingStart,
                    reactionsView.paddingTop,
                    neededPadding,
                    reactionsView.paddingBottom
                )
                true
            } else false
        } else {
            
            val defaultPadding = REACTIONS_SIDE_PADDING_DP.dp
            if (reactionsView.paddingEnd != defaultPadding) {
                reactionsView.setPaddingRelative(
                    reactionsView.paddingStart,
                    reactionsView.paddingTop,
                    defaultPadding,
                    reactionsView.paddingBottom
                )
                true
            } else false
        }
    }

    private fun adjustTextPadding(textView: TextView, needsExtraBottomSpace: Boolean, timeViewWidth: Int): Boolean {
        return if (!needsExtraBottomSpace) {
            
            textView.layout?.let { layout ->
                if (layout.lineCount > 0) {
                    
                    val lastLineIndex = layout.lineCount - 1
                    val lastLineWidth = layout.getLineRight(lastLineIndex) - layout.getLineLeft(lastLineIndex)

                    
                    var maxLineWidth = 0f
                    for (i in 0 until layout.lineCount) {
                        val lineWidth = layout.getLineRight(i) - layout.getLineLeft(i)
                        maxLineWidth = maxOf(maxLineWidth, lineWidth)
                    }

                    
                    val neededPadding = ((lastLineWidth + timeViewWidth) - maxLineWidth).toInt()
                    val finalPadding = maxOf(12.dp, neededPadding) 

                    if (textView.paddingEnd != finalPadding) {
                        textView.setPaddingRelative(
                            textView.paddingStart,
                            textView.paddingTop,
                            finalPadding,
                            textView.paddingBottom
                        )
                        true
                    } else {
                        false
                    }
                } else false
            } ?: false
        } else {
            
            val defaultPadding = 12.dp
            if (textView.paddingEnd != defaultPadding) {
                textView.setPaddingRelative(
                    textView.paddingStart,
                    textView.paddingTop,
                    defaultPadding,
                    textView.paddingBottom
                )
                true
            } else false
        }
    }

    





    @VisibleForTesting
    internal fun calculateEffectiveMaxWidth(): Int {
        
        val parentView = parent as? ViewGroup

        if (parentView != null) {
            
            var horizontalSpaceUsed = 0
            
            
            var goneMarginReplacesStart = false

            
            val checkbox = parentView.findViewById<View>(R.id.checkbox_select_for_unpin)
            if (checkbox != null) {
                if (checkbox.isVisible) {
                    horizontalSpaceUsed += checkbox.measuredWidth
                    
                    val checkboxParams = checkbox.layoutParams as? MarginLayoutParams
                    checkboxParams?.let {
                        horizontalSpaceUsed += it.marginStart + it.marginEnd
                    }
                } else if (checkbox.isGone) {
                    
                    
                    
                    val goneStart = (layoutParams as? ConstraintLayout.LayoutParams)?.goneStartMargin ?: 0
                    goneMarginReplacesStart = !sent && goneStart > 0
                    if (goneMarginReplacesStart) horizontalSpaceUsed += goneStart
                }
            }

            
            
            
            
            
            
            val availableWidth = chatContainerWidthPx()

            
            val ourParams = layoutParams as? MarginLayoutParams
            val ourMargins = ourParams?.let {
                (if (goneMarginReplacesStart) 0 else it.marginStart) + it.marginEnd
            } ?: 0

            
            val effectiveWidth = capped(availableWidth - horizontalSpaceUsed - ourMargins) - paddingLeft - paddingRight

            
            if (effectiveWidth > 100.dp) {
                return effectiveWidth
            }
        }

        
        val screenWidth = chatContainerWidthPx()
        
        val marginsDp = 60
        val marginsPixels = (marginsDp * resources.displayMetrics.density).toInt()
        val maxBubbleWidth = capped(screenWidth - marginsPixels)

        return maxBubbleWidth - paddingLeft - paddingRight
    }

    private fun capped(bubbleWidth: Int): Int = if (capsWidth) minOf(bubbleWidth, maxBubblePx()) else bubbleWidth

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun resetAllPaddingsToDefault(): Boolean {
        var paddingChanged = false

        
        val contentFrame = findViewById<View>(R.id.contentFrame)
        if (contentFrame?.visibility == VISIBLE) {
            val textView = contentFrame.findViewById<TextView>(R.id.textView)
            textView?.let { tv ->
                val defaultPadding = 12.dp
                if (tv.paddingEnd != defaultPadding) {
                    tv.setPaddingRelative(
                        tv.paddingStart,
                        tv.paddingTop,
                        defaultPadding,
                        tv.paddingBottom
                    )
                    paddingChanged = true
                }
            }
        }

        
        val reactionsView = findViewById<View>(R.id.reactions_view)
        if (reactionsView?.visibility == VISIBLE && reactionsView is FlowLayout) {
            val defaultPadding = REACTIONS_SIDE_PADDING_DP.dp
            if (reactionsView.paddingEnd != defaultPadding) {
                reactionsView.setPaddingRelative(
                    reactionsView.paddingStart,
                    reactionsView.paddingTop,
                    defaultPadding,
                    reactionsView.paddingBottom
                )
                paddingChanged = true
            }
        }

        return paddingChanged
    }

    internal companion object {
        private const val QUOTE_CHROME_DP = 47
        private const val QUOTE_CHROME_WITH_THUMBNAIL_DP = 97
        private const val QUOTE_TEXT_MIN_DP = 40

        
        const val AVATAR_COLUMN_EXTRA_DP = 20

        
        private const val TIME_BELOW_TAG = "time_below"
        private const val CARD_TIME_BOTTOM_DP = 3

        
        private const val REACTIONS_SIDE_PADDING_DP = 12
    }
}
