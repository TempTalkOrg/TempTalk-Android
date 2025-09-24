package com.difft.android.chat.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.difft.android.chat.R

class ChatMessageContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // First measure to get initial sizes
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // Get the time view and its width
        val timeView = findViewById<View>(R.id.cl_message_time) ?: return
        val timeViewWidth = timeView.measuredWidth

        // Check if time view is not visible or has zero width
        if (timeView.visibility != VISIBLE || timeViewWidth == 0) {
            // Reset all paddings to default when no time view is displayed
            val paddingChanged = resetAllPaddingsToDefault()

            // Re-measure if padding changed
            if (paddingChanged) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }

            return
        }

        // Calculate the maximum possible width for content
        val effectiveMaxWidth = calculateEffectiveMaxWidth(widthMeasureSpec)

        // Determine if we need time view below based on the last visible content view
        var needsExtraBottomSpace = false
        var needsRemeasure = false

        // Check reactions view if visible
        val reactionsView = findViewById<View>(R.id.reactions_view)
        if (reactionsView?.visibility == VISIBLE && reactionsView is FlowLayout) {
            needsExtraBottomSpace = shouldPlaceTimeBelowReactions(reactionsView, timeViewWidth, effectiveMaxWidth)
            needsRemeasure = adjustReactionsPadding(reactionsView, needsExtraBottomSpace, timeViewWidth)
        } else {
            // Check text view if no reactions
            val contentFrame = findViewById<View>(R.id.contentFrame)
            if (contentFrame?.visibility == VISIBLE) {
                val textView = contentFrame.findViewById<TextView>(R.id.textView)
                textView?.let { tv ->
                    needsExtraBottomSpace = shouldPlaceTimeBelowText(tv, timeViewWidth, effectiveMaxWidth)
                    needsRemeasure = adjustTextPadding(tv, needsExtraBottomSpace, timeViewWidth)
                }
            } else {
                // Check forward content last
                val forwardContent = findViewById<View>(R.id.cl_forward_content)
                val tvForwardContent = findViewById<View>(R.id.tv_forward_content)
                val forwardContentCover = findViewById<View>(R.id.v_forward_cover)
                if (tvForwardContent?.visibility == VISIBLE && forwardContentCover?.visibility != VISIBLE) {
                    val messageForwardView = forwardContent.findViewById<View>(R.id.messageForwardView)
                    messageForwardView?.let { forwardView ->
                        needsExtraBottomSpace = shouldPlaceTimeBelowForward(forwardView, timeViewWidth, effectiveMaxWidth)
                        needsRemeasure = adjustForwardPadding(forwardView, needsExtraBottomSpace, timeViewWidth)
                    }
                }
            }
        }

        // Adjust time view margin based on needsExtraBottomSpace
        val timeParams = timeView.layoutParams as? MarginLayoutParams
        timeParams?.let { params ->
            val newMarginTop = if (needsExtraBottomSpace) 0 else (-26).dp
            if (params.topMargin != newMarginTop) {
                params.topMargin = newMarginTop
                timeView.layoutParams = params
                needsRemeasure = true
            }
        }

        // Re-measure if we made any changes
        if (needsRemeasure) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun shouldPlaceTimeBelowReactions(reactionsView: FlowLayout, timeViewWidth: Int, effectiveMaxWidth: Int): Boolean {
        val lastLineWidth = reactionsView.getLastLineWidth()

        // For both single and multi-line reactions, check if time can fit on the last line
        val totalWidth = lastLineWidth + timeViewWidth
        return totalWidth > effectiveMaxWidth
    }

    private fun shouldPlaceTimeBelowText(textView: TextView, timeViewWidth: Int, effectiveMaxWidth: Int): Boolean {
        textView.layout?.let { layout ->
            if (layout.lineCount > 0) {
                // Get the last line width (works for both single and multi-line)
                val lastLineIndex = layout.lineCount - 1
                val lastLineWidth = layout.getLineRight(lastLineIndex) - layout.getLineLeft(lastLineIndex)

                // Check if time can fit on the last line
                // Include TextView's paddingStart since it takes up space
                val totalWidth = textView.paddingStart + lastLineWidth + timeViewWidth
                val result = totalWidth > effectiveMaxWidth

                return result
            }
        }
        return false
    }

    private fun adjustReactionsPadding(reactionsView: FlowLayout, needsExtraBottomSpace: Boolean, timeViewWidth: Int): Boolean {
        // Only add padding for single-line reactions when time is inline
        return if (!needsExtraBottomSpace && reactionsView.getLineCount() == 1) {
            // Single line with inline time - add padding for time view
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
            // Multi-line or time below - use default padding
            val defaultPadding = 8.dp
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
            // Time is inline - calculate dynamic padding based on actual line widths
            textView.layout?.let { layout ->
                if (layout.lineCount > 0) {
                    // Get last line width
                    val lastLineIndex = layout.lineCount - 1
                    val lastLineWidth = layout.getLineRight(lastLineIndex) - layout.getLineLeft(lastLineIndex)

                    // Find max line width
                    var maxLineWidth = 0f
                    for (i in 0 until layout.lineCount) {
                        val lineWidth = layout.getLineRight(i) - layout.getLineLeft(i)
                        maxLineWidth = maxOf(maxLineWidth, lineWidth)
                    }

                    // Calculate needed padding using formula: lastLine + timeView - maxLine
                    val neededPadding = ((lastLineWidth + timeViewWidth) - maxLineWidth).toInt()
                    val finalPadding = maxOf(12.dp, neededPadding) // Ensure minimum padding

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
            // Time is below - use default padding
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

    private fun shouldPlaceTimeBelowForward(forwardView: View, timeViewWidth: Int, effectiveMaxWidth: Int): Boolean {
        // For forward messages, check the text content inside the forward view
        // Look for the last line of text in the forward message
        val forwardTextViews = findTextViewsInForward(forwardView)

        if (forwardTextViews.isNotEmpty()) {
            // Get the last text view's last line width
            val lastTextView = forwardTextViews.last()
            lastTextView.layout?.let { layout ->
                if (layout.lineCount > 0) {
                    val lastLineIndex = layout.lineCount - 1
                    val lastLineWidth = layout.getLineRight(lastLineIndex) - layout.getLineLeft(lastLineIndex)

                    // Check if time can fit on the last line
                    val totalWidth = lastLineWidth + timeViewWidth
                    return totalWidth > effectiveMaxWidth
                }
            }
        }

        // Fallback: check the whole forward view width
        val forwardWidth = forwardView.measuredWidth
        val totalWidth = forwardWidth + timeViewWidth
        return totalWidth > effectiveMaxWidth
    }

    private fun findTextViewsInForward(forwardView: View): List<TextView> {
        val textViews = mutableListOf<TextView>()

        // Recursively find all TextViews in the forward view
        fun findTextViewsRecursive(view: View) {
            if (view is TextView && view.isVisible && view.text.isNotEmpty()) {
                textViews.add(view)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child.isVisible) {
                        findTextViewsRecursive(child)
                    }
                }
            }
        }

        findTextViewsRecursive(forwardView)
        return textViews
    }

    private fun adjustForwardPadding(forwardView: View, needsExtraBottomSpace: Boolean, timeViewWidth: Int): Boolean {
        // Forward views typically have their own padding handled by MessageForwardView
        // We need to adjust the parent container padding instead
        val forwardContainer = forwardView.parent as? View ?: return false

        return if (!needsExtraBottomSpace) {
            // Time is inline - calculate dynamic padding based on actual text widths
            val forwardTextViews = findTextViewsInForward(forwardView)

            if (forwardTextViews.isNotEmpty()) {
                // Find the last text view with content
                val lastTextView = forwardTextViews.last()
                lastTextView.layout?.let { layout ->
                    if (layout.lineCount > 0) {
                        // Get last line width of the last text view
                        val lastLineIndex = layout.lineCount - 1
                        val lastLineWidth = layout.getLineRight(lastLineIndex) - layout.getLineLeft(lastLineIndex)

                        // Find max line width across all text views
                        var maxLineWidth = 0f
                        forwardTextViews.forEach { tv ->
                            tv.layout?.let { tvLayout ->
                                for (i in 0 until tvLayout.lineCount) {
                                    val lineWidth = tvLayout.getLineRight(i) - tvLayout.getLineLeft(i)
                                    maxLineWidth = maxOf(maxLineWidth, lineWidth)
                                }
                            }
                        }

                        // Calculate needed padding using formula: lastLine + timeView - maxLine
                        val neededPadding = ((lastLineWidth + timeViewWidth) - maxLineWidth).toInt()
                        val finalPadding = maxOf(8.dp, neededPadding) // Ensure minimum padding

                        if (forwardContainer.paddingEnd != finalPadding) {
//                            forwardContainer.setPaddingRelative(
//                                forwardContainer.paddingStart,
//                                forwardContainer.paddingTop,
//                                finalPadding,
//                                forwardContainer.paddingBottom
//                            )
                            true
                        } else false
                    } else false
                } ?: false
            } else {
                // Fallback if no text views found
                val defaultInlinePadding = timeViewWidth + 3.dp
                if (forwardContainer.paddingEnd != defaultInlinePadding) {
                    forwardContainer.setPaddingRelative(
                        forwardContainer.paddingStart,
                        forwardContainer.paddingTop,
                        defaultInlinePadding,
                        forwardContainer.paddingBottom
                    )
                    true
                } else false
            }
        } else {
            // Time is below - use default padding
            val defaultPadding = 0.dp
            if (forwardContainer.paddingEnd != defaultPadding) {
                forwardContainer.setPaddingRelative(
                    forwardContainer.paddingStart,
                    forwardContainer.paddingTop,
                    defaultPadding,
                    forwardContainer.paddingBottom
                )
                true
            } else false
        }
    }

    private fun calculateEffectiveMaxWidth(widthMeasureSpec: Int): Int {
        // Try to get the parent ChatMessageItemView for more accurate calculations
        val parentView = parent as? ViewGroup

        if (parentView != null) {
            // Calculate space taken by other views in the same horizontal space
            var horizontalSpaceUsed = 0

            // Check for checkbox (usually on the left side)
            val checkbox = parentView.findViewById<View>(R.id.checkbox_select_for_unpin)
            if (checkbox != null) {
                if (checkbox.isVisible) {
                    horizontalSpaceUsed += checkbox.measuredWidth
                    // Add checkbox margin
                    val checkboxParams = checkbox.layoutParams as? MarginLayoutParams
                    checkboxParams?.let {
                        horizontalSpaceUsed += it.marginStart + it.marginEnd
                    }
                } else if (checkbox.isGone) {
                    // When checkbox is GONE, account for layout_goneMarginStart="26dp"
                    horizontalSpaceUsed += 26.dp
                }
            }

            // Use screen width as the available width
            val availableWidth = resources.displayMetrics.widthPixels

            // Get our own margins
            val ourParams = layoutParams as? MarginLayoutParams
            val ourMargins = ourParams?.let {
                it.marginStart + it.marginEnd
            } ?: 0

            // Calculate effective max width
            val effectiveWidth = availableWidth - horizontalSpaceUsed - ourMargins - paddingLeft - paddingRight

            // Ensure we have a reasonable minimum width
            if (effectiveWidth > 100.dp) {
                return effectiveWidth
            }
        }

        // Fallback to screen-based calculation if parent info is not available
        val screenWidth = resources.displayMetrics.widthPixels
        // Account for typical margins: 40dp on one side + 8-12dp on the other
        val marginsDp = 60
        val marginsPixels = (marginsDp * resources.displayMetrics.density).toInt()
        val maxBubbleWidth = screenWidth - marginsPixels

        return maxBubbleWidth - paddingLeft - paddingRight
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun resetAllPaddingsToDefault(): Boolean {
        var paddingChanged = false

        // Reset text view padding to default
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

        // Reset reactions view padding to default
        val reactionsView = findViewById<View>(R.id.reactions_view)
        if (reactionsView?.visibility == VISIBLE && reactionsView is FlowLayout) {
            val defaultPadding = 8.dp
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

        // Reset forward content container padding to default
        val forwardContent = findViewById<View>(R.id.cl_forward_content)
        if (forwardContent?.visibility == VISIBLE) {
            val messageForwardView = forwardContent.findViewById<View>(R.id.messageForwardView)
            messageForwardView?.let { forwardView ->
                val forwardContainer = forwardView.parent as? View
                forwardContainer?.let { container ->
                    val defaultPadding = 0.dp
                    if (container.paddingEnd != defaultPadding) {
                        container.setPaddingRelative(
                            container.paddingStart,
                            container.paddingTop,
                            defaultPadding,
                            container.paddingBottom
                        )
                        paddingChanged = true
                    }
                }
            }
        }

        return paddingChanged
    }
}