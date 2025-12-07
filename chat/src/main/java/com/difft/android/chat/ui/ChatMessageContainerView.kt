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
        // First, always reset all paddings to ensure clean state for RecyclerView reuse
        // This prevents padding from previous item from affecting current item
        resetAllPaddingsToDefault()

        // First measure to get initial sizes
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // Get the time view and its width
        val timeView = findViewById<View>(R.id.cl_message_time) ?: return
        val timeViewWidth = timeView.measuredWidth

        // Check if time view is not visible or has zero width
        if (timeView.visibility != VISIBLE || timeViewWidth == 0) {
            // Already reset, just return
            return
        }

        // Calculate the maximum possible width for content
        val effectiveMaxWidth = calculateEffectiveMaxWidth()

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

    private fun calculateEffectiveMaxWidth(): Int {
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

        return paddingChanged
    }
}