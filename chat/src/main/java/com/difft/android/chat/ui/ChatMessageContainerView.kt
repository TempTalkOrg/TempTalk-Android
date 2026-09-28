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
import com.difft.android.chat.widget.chatContainerWidthPx

class ChatMessageContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Reset paddings to default before measuring to ensure clean state for RecyclerView reuse
        resetAllPaddingsToDefault()

        // First measure to get initial sizes
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // Time wrapper holds icon + cl_message_time; used for margin adjustments.
        // Guarded (not early-return) so the quote trailing-align below always runs.
        val timeWrapper = findViewById<View>(R.id.ll_time_wrapper)
        if (timeWrapper != null && timeWrapper.visibility == VISIBLE) {
            val clMessageTime = findViewById<View>(R.id.cl_message_time)
            val icon = findViewById<View>(R.id.iv_confidential_icon)
            var timeViewWidth = if (clMessageTime != null && clMessageTime.isVisible) clMessageTime.measuredWidth else 0
            // Confidential icon visible: fixed offset covers icon(22) + gap(3) + margin(8) + buffer(7)
            if (icon != null && icon.isVisible) {
                timeViewWidth += 40.dp
            }

            if (timeViewWidth > 0) {
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

                // Adjust time wrapper margin based on needsExtraBottomSpace
                val timeParams = timeWrapper.layoutParams as? MarginLayoutParams
                timeParams?.let { params ->
                    val newMarginTop = if (needsExtraBottomSpace) 0 else (-26).dp
                    if (params.topMargin != newMarginTop) {
                        params.topMargin = newMarginTop
                        // Don't call setLayoutParams() — params is already the same reference,
                        // and setLayoutParams() triggers requestLayout() which can cause
                        // re-entrant layout issues on Android 16.
                        needsRemeasure = true
                    }
                }

                // Re-measure if we made any changes
                if (needsRemeasure) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }
        }

        // After all measure passes: stretch the quote row to the bubble's content width so its
        // trailing weighted spacer right-aligns the thumbnail (matches desktop). The quote row keeps
        // wrap_content layout params, so its intrinsic width still grew the bubble above; here we only
        // widen it to the already-decided width (never beyond), so the bubble never grows from this.
        alignQuoteThumbnailToTrailing()
    }

    /**
     * Right-aligns the quote thumbnail to the bubble's content width. After the bubble width is
     * settled, the quote row is wrap_content and left-packed; re-measuring it at the full content
     * width lets its weighted spacer push the thumbnail to the trailing edge. Re-runs on every
     * measure pass, so an async-loaded thumbnail (reverse-lookup) is handled without stale state:
     * the load flips the thumbnail GONE->VISIBLE, which itself requests a layout, so the next pass
     * runs the stretch. Text-only quotes (thumbnail GONE) have nothing to align, so skip the work.
     */
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

    /**
     * Calculate the maximum possible width for content.
     * This is NOT the same as measuredWidth - we need to know "how wide CAN this be"
     * not "how wide IS it currently". Short messages have small measuredWidth but
     * could still fit the time view on the same line.
     */
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

            // Conversation viewport width from the message RecyclerView ancestor (ContentSize.kt):
            // correct in the dual-pane detail pane, tracks a pane-divider drag on the next
            // measure pass, and is allocation-free — this runs per measure, where
            // WindowMetricsCalculator (reflection on API 26-29) is not affordable. Deliberately
            // UNCAPPED: this asks "how much room does the bubble actually have", and a
            // wrap_content text bubble can occupy the full container.
            val availableWidth = chatContainerWidthPx()

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

        // Fallback calculation if parent info is not available
        val screenWidth = chatContainerWidthPx()
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