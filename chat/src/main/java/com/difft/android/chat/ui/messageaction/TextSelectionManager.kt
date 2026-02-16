package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import android.text.Layout
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Manages custom text selection for a TextView.
 * 
 * This provides a fully custom selection implementation that doesn't rely on
 * Android's textIsSelectable, avoiding conflicts with overlay menus and providing
 * complete control over the selection UI and behavior.
 * 
 * Features:
 * - Selection highlight drawing
 * - Draggable selection handles
 * - Character-level selection (no smart word detection)
 * - Enable/disable toggle for sensitive messages
 * 
 * Usage:
 * 1. Create instance with target TextView
 * 2. Call attachToOverlay() to add handles and highlight to the overlay container
 * 3. Call selectAll() or setSelection() to start selection
 * 4. Listen to selection changes via onSelectionChanged callback
 * 5. Call detach() when done
 */
class TextSelectionManager(
    private val textView: TextView
) {
    
    /**
     * Callback for selection changes
     */
    interface SelectionCallback {
        fun onSelectionChanged(start: Int, end: Int, isFullSelect: Boolean)
    }
    
    /**
     * Callback for handle position changes during drag.
     * Used by TextPreviewActivity for auto-scroll when handle reaches edge.
     */
    interface HandleDragCallback {
        /**
         * Called when handle position changes during drag.
         * @param screenY The Y position of the handle on screen
         * @param isStart Whether this is the start handle (true) or end handle (false)
         */
        fun onHandleDrag(screenY: Float, isStart: Boolean)
        
        /**
         * Called when handle drag ends.
         */
        fun onHandleDragEnd()
    }
    
    // Selection state
    private var selectionStart = 0
    private var selectionEnd = 0
    private var isEnabled = true
    private var isDragging = false
    
    // UI components
    private var overlayContainer: ViewGroup? = null
    private var highlightView: SelectionHighlightView? = null
    private var startHandle: SelectionHandleView? = null
    private var endHandle: SelectionHandleView? = null
    
    // View to constrain highlight bounds (e.g., RecyclerView)
    private var clipBoundsView: View? = null
    
    // Callbacks
    var onSelectionChanged: SelectionCallback? = null
    var handleDragCallback: HandleDragCallback? = null
    
    /**
     * Enable or disable text selection.
     * When disabled, no selection UI will be shown.
     */
    fun setEnabled(enabled: Boolean) {
        this.isEnabled = enabled
        if (!enabled) {
            clearSelection()
        }
    }
    
    /**
     * Check if selection is enabled.
     */
    fun isEnabled(): Boolean = isEnabled
    
    /**
     * Attach selection UI to an overlay container.
     * The highlight and handles will be positioned relative to the textView's location.
     * 
     * @param container The overlay container (should be full-screen or cover the textView area)
     * @param clipBoundsView Optional view to constrain highlight bounds (e.g., RecyclerView).
     *        When provided, the highlight will be clipped to this view's visible bounds.
     */
    fun attachToOverlay(container: ViewGroup, clipBoundsView: View? = null) {
        if (!isEnabled) return
        
        this.overlayContainer = container
        this.clipBoundsView = clipBoundsView
        
        // Create highlight view
        highlightView = SelectionHighlightView(textView.context).apply {
            bindToTextView(textView)
        }
        
        // Create selection handles
        startHandle = SelectionHandleView(textView.context, isStart = true).apply {
            visibility = View.GONE
            onDragStart = { isDragging = true }
            onDragEnd = { 
                isDragging = false
                // Snap handle to correct position after drag
                updateHandlePositions()
                notifySelectionChanged()
                handleDragCallback?.onHandleDragEnd()
            }
            onDrag = { deltaX, deltaY -> handleDrag(isStart = true, deltaX, deltaY) }
        }
        
        endHandle = SelectionHandleView(textView.context, isStart = false).apply {
            visibility = View.GONE
            onDragStart = { isDragging = true }
            onDragEnd = { 
                isDragging = false
                // Snap handle to correct position after drag
                updateHandlePositions()
                notifySelectionChanged()
                handleDragCallback?.onHandleDragEnd()
            }
            onDrag = { deltaX, deltaY -> handleDrag(isStart = false, deltaX, deltaY) }
        }
        
        // Add views to overlay
        // Order: highlight first (background), then handles on top
        container.addView(highlightView, FrameLayout.LayoutParams(
            textView.width,
            textView.height
        ))
        
        container.addView(startHandle, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        
        container.addView(endHandle, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // Ensure handles are on top with elevation
        startHandle?.elevation = 10f
        endHandle?.elevation = 10f
        
        // Position highlight view to match textView (after adding to container)
        updateHighlightViewPosition()
    }
    
    /**
     * Detach from overlay and clean up resources.
     */
    fun detach() {
        overlayContainer?.let { container ->
            highlightView?.let { container.removeView(it) }
            startHandle?.let { container.removeView(it) }
            endHandle?.let { container.removeView(it) }
        }
        highlightView = null
        startHandle = null
        endHandle = null
        overlayContainer = null
        selectionStart = 0
        selectionEnd = 0
    }
    
    /**
     * Transfer handles and highlight to a new overlay container.
     * Used when switching from MessageActionPopup to TextSelectionPopup.
     */
    fun transferToOverlay(newContainer: ViewGroup) {
        val oldContainer = overlayContainer ?: return
        
        // Remove from old container
        highlightView?.let { oldContainer.removeView(it) }
        startHandle?.let { oldContainer.removeView(it) }
        endHandle?.let { oldContainer.removeView(it) }
        
        // Add to new container
        overlayContainer = newContainer
        
        highlightView?.let { newContainer.addView(it) }
        startHandle?.let { newContainer.addView(it) }
        endHandle?.let { newContainer.addView(it) }
        
        // Update positions for new container
        updateHighlightViewPosition()
        updateHandlePositions()
    }
    
    /**
     * Select all text in the TextView.
     */
    fun selectAll() {
        if (!isEnabled) return
        
        val text = textView.text ?: return
        setSelection(0, text.length)
    }
    
    /**
     * Set selection range.
     * 
     * @param start Selection start index (inclusive)
     * @param end Selection end index (exclusive)
     */
    fun setSelection(start: Int, end: Int) {
        if (!isEnabled) return
        
        val text = textView.text ?: return
        val textLength = text.length
        
        // Clamp to valid range
        var newStart = start.coerceIn(0, textLength)
        var newEnd = end.coerceIn(0, textLength)
        
        // Ensure start <= end
        if (newStart > newEnd) {
            val temp = newStart
            newStart = newEnd
            newEnd = temp
        }
        
        selectionStart = newStart
        selectionEnd = newEnd
        
        updateUI()
        
        // Only notify if not currently dragging (avoid excessive callbacks during drag)
        if (!isDragging) {
            notifySelectionChanged()
        }
    }
    
    /**
     * Clear selection.
     */
    fun clearSelection() {
        selectionStart = 0
        selectionEnd = 0
        
        highlightView?.clearSelection()
        startHandle?.visibility = View.GONE
        endHandle?.visibility = View.GONE
    }
    
    /**
     * Get the currently selected text.
     */
    fun getSelectedText(): String {
        val text = textView.text ?: return ""
        return if (selectionStart < selectionEnd && selectionStart >= 0 && selectionEnd <= text.length) {
            text.subSequence(selectionStart, selectionEnd).toString()
        } else {
            ""
        }
    }
    
    /**
     * Get selection start index.
     */
    fun getSelectionStart(): Int = selectionStart
    
    /**
     * Get selection end index.
     */
    fun getSelectionEnd(): Int = selectionEnd
    
    /**
     * Check if there is any selection.
     */
    fun hasSelection(): Boolean = selectionStart < selectionEnd
    
    /**
     * Check if all text is selected.
     */
    fun isFullSelection(): Boolean {
        val textLength = textView.text?.length ?: 0
        return selectionStart == 0 && selectionEnd == textLength && textLength > 0
    }
    
    /**
     * Check if currently dragging a handle.
     */
    fun isDragging(): Boolean = isDragging
    
    /**
     * Update UI positions after scroll.
     * Call this when the parent ScrollView scrolls to keep handles and highlight in sync.
     */
    fun updateUIAfterScroll() {
        updateHighlightViewPosition()
        updateHighlight()
        // Note: Don't update handle positions during drag as they follow the finger
        if (!isDragging) {
            updateHandlePositions()
        }
    }
    
    /**
     * Select the word at the given character offset.
     * If the offset is on whitespace, selects the nearest word.
     * 
     * @param offset Character offset in the text
     */
    fun selectWordAt(offset: Int) {
        if (!isEnabled) return
        
        val text = textView.text?.toString() ?: return
        if (text.isEmpty()) return
        
        val clampedOffset = offset.coerceIn(0, text.length)
        
        // Find word boundaries
        var start = clampedOffset
        var end = clampedOffset
        
        // Expand left to find word start
        while (start > 0 && !text[start - 1].isWhitespace()) {
            start--
        }
        
        // Expand right to find word end
        while (end < text.length && !text[end].isWhitespace()) {
            end++
        }
        
        // If we're on whitespace, try to select the word before or after
        if (start == end) {
            // Try word before
            if (start > 0) {
                end = start
                while (start > 0 && !text[start - 1].isWhitespace()) {
                    start--
                }
            }
            // If still no selection, try word after
            if (start == end && end < text.length) {
                start = end
                while (end < text.length && !text[end].isWhitespace()) {
                    end++
                }
            }
        }
        
        // Ensure we have a valid selection
        if (start < end) {
            setSelection(start, end)
        }
    }
    
    /**
     * Get the selection bounds in screen coordinates.
     * Returns null if there's no selection or layout is not ready.
     * 
     * @return Rect with (left, top, right, bottom) of the selection bounds in screen coordinates
     */
    fun getSelectionBoundsOnScreen(): Rect? {
        if (!hasSelection()) return null
        
        val layout = textView.layout ?: return null
        val container = overlayContainer ?: return null
        
        // Get positions
        val textViewLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        
        val offsetX = textViewLocation[0] + textView.paddingLeft
        val offsetY = textViewLocation[1] + textView.paddingTop
        
        // Get start line bounds
        val startLine = layout.getLineForOffset(selectionStart)
        val startLineTop = layout.getLineTop(startLine)
        
        // Get end line bounds
        val endLine = layout.getLineForOffset(selectionEnd)
        val endLineBottom = layout.getLineBottom(endLine)
        
        // Get horizontal bounds
        val startX = layout.getPrimaryHorizontal(selectionStart)
        val endX = layout.getPrimaryHorizontal(selectionEnd)
        
        // For multi-line selection, use full width
        val left: Int
        val right: Int
        if (startLine == endLine) {
            left = (offsetX + minOf(startX, endX)).toInt()
            right = (offsetX + maxOf(startX, endX)).toInt()
        } else {
            left = offsetX
            right = offsetX + textView.width - textView.paddingLeft - textView.paddingRight
        }
        
        return Rect(
            left,
            offsetY + startLineTop,
            right,
            offsetY + endLineBottom
        )
    }
    
    /**
     * Extend selection to the character at the given screen position.
     * Used for long-press-and-drag selection behavior.
     * The selection start stays fixed, only the end is updated.
     * 
     * Note: This method does NOT trigger onSelectionChanged callback to avoid
     * menu flickering during drag. The caller should show the menu after drag ends.
     * 
     * @param screenX X coordinate in screen space
     * @param screenY Y coordinate in screen space
     * @param anchorStart The fixed anchor point (start of selection that won't change)
     */
    fun extendSelectionToScreenPosition(screenX: Float, screenY: Float, anchorStart: Int) {
        if (!isEnabled) return
        
        val offset = getOffsetForScreenPosition(screenX, screenY)
        if (offset < 0) return
        
        // Extend selection from anchor to current position (without triggering callback)
        val newStart: Int
        val newEnd: Int
        if (offset < anchorStart) {
            newStart = offset
            newEnd = anchorStart
        } else {
            newStart = anchorStart
            newEnd = offset
        }
        
        // Only update if changed
        if (newStart != selectionStart || newEnd != selectionEnd) {
            selectionStart = newStart
            selectionEnd = newEnd
            updateUI()
            // Note: intentionally NOT calling notifySelectionChanged() to avoid menu flicker
        }
    }
    
    /**
     * Get the character offset at the given screen position.
     * 
     * @param screenX X coordinate in screen space
     * @param screenY Y coordinate in screen space
     * @return Character offset, or -1 if position is invalid
     */
    fun getOffsetForScreenPosition(screenX: Float, screenY: Float): Int {
        val layout = textView.layout ?: return -1
        
        // Get text view location
        val textViewLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        
        // Convert to text view local coordinates
        val localX = screenX - textViewLocation[0] - textView.paddingLeft
        val localY = screenY - textViewLocation[1] - textView.paddingTop
        
        // Find line at Y position
        val line = when {
            localY < 0 -> 0
            localY >= layout.height -> layout.lineCount - 1
            else -> layout.getLineForVertical(localY.toInt())
        }
        
        // Find character offset at X position on this line
        return layout.getOffsetForHorizontal(line, localX)
    }
    
    // ========================
    // Private implementation
    // ========================
    
    private fun updateUI() {
        updateHighlightViewPosition()
        updateHighlight()
        updateHandlePositions()
    }
    
    private fun updateHighlightViewPosition() {
        val container = overlayContainer ?: return
        val highlight = highlightView ?: return
        
        // Get textView position relative to overlay container
        val textViewLocation = IntArray(2)
        val containerLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        container.getLocationOnScreen(containerLocation)
        
        val offsetX = textViewLocation[0] - containerLocation[0]
        val offsetY = textViewLocation[1] - containerLocation[1]
        
        // Update highlight view position and size
        highlight.x = offsetX.toFloat()
        highlight.y = offsetY.toFloat()
        
        highlight.layoutParams?.let { params ->
            params.width = textView.width
            params.height = textView.height
            highlight.layoutParams = params
        }
        
        // Calculate and apply clip bounds if a bounds view is provided
        clipBoundsView?.let { boundsView ->
            val boundsViewLocation = IntArray(2)
            boundsView.getLocationOnScreen(boundsViewLocation)
            
            // Calculate clip bounds relative to highlight view's position
            // highlightView is at (offsetX, offsetY) in container coordinates
            // We need bounds relative to highlightView's own coordinate system (0,0 is top-left of highlightView)
            val clipLeft = (boundsViewLocation[0] - textViewLocation[0]).coerceAtLeast(0)
            val clipTop = (boundsViewLocation[1] - textViewLocation[1]).coerceAtLeast(0)
            val clipRight = (boundsViewLocation[0] + boundsView.width - textViewLocation[0]).coerceAtMost(textView.width)
            val clipBottom = (boundsViewLocation[1] + boundsView.height - textViewLocation[1]).coerceAtMost(textView.height)
            
            highlight.clipBounds = Rect(clipLeft, clipTop, clipRight, clipBottom)
        }
    }
    
    private fun updateHighlight() {
        highlightView?.setSelection(selectionStart, selectionEnd)
    }
    
    private fun updateHandlePositions() {
        val layout = textView.layout ?: return
        val container = overlayContainer ?: return
        
        if (!hasSelection()) {
            startHandle?.visibility = View.GONE
            endHandle?.visibility = View.GONE
            return
        }
        
        // Get textView position relative to overlay container
        val textViewLocation = IntArray(2)
        val containerLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        container.getLocationOnScreen(containerLocation)
        
        val offsetX = textViewLocation[0] - containerLocation[0] + textView.paddingLeft
        val offsetY = textViewLocation[1] - containerLocation[1] + textView.paddingTop
        
        // Calculate start handle position
        // Start handle: circle at top, line points down INTO the text line
        // Line should span the entire text line height (from line top to line bottom)
        val startLine = layout.getLineForOffset(selectionStart)
        val startX = layout.getPrimaryHorizontal(selectionStart)
        val startLineTop = layout.getLineTop(startLine).toFloat()
        val startLineBottom = layout.getLineBottom(startLine).toFloat()
        val startLineHeight = startLineBottom - startLineTop
        
        // Get clip bounds view location for handle visibility check
        val boundsViewLocation = IntArray(2)
        val boundsTop: Int
        val boundsBottom: Int
        if (clipBoundsView != null) {
            clipBoundsView!!.getLocationOnScreen(boundsViewLocation)
            boundsTop = boundsViewLocation[1]
            boundsBottom = boundsViewLocation[1] + clipBoundsView!!.height
        } else {
            boundsTop = Int.MIN_VALUE
            boundsBottom = Int.MAX_VALUE
        }
        
        startHandle?.let { handle ->
            // Set line height to match text line height
            handle.setLineHeight(startLineHeight)
            
            val handleX = (offsetX + startX - handle.pivotOffsetX).toInt()
            // Position so handle's line covers the text line (line bottom at text line bottom)
            // Handle structure: [circle (radius*2)] + [line (lineHeight)]
            // pivotOffsetY = totalHeight = radius*2 + lineHeight
            // We want: handleY + pivotOffsetY = text line bottom
            val handleY = (offsetY + startLineBottom - handle.pivotOffsetY).toInt()
            
            // Check if handle is within visible bounds
            val handleScreenY = containerLocation[1] + handleY
            val handleBottomScreenY = handleScreenY + handle.totalHeight.toInt()
            val isVisible = handleBottomScreenY > boundsTop && handleScreenY < boundsBottom
            handle.visibility = if (isVisible) View.VISIBLE else View.GONE
            
            // Use layout params to position the handle (so touch detection works correctly)
            val params = handle.layoutParams as? FrameLayout.LayoutParams
            if (params != null) {
                params.leftMargin = handleX
                params.topMargin = handleY
                handle.layoutParams = params
            }
            // Reset translation in case it was set during dragging
            handle.translationX = 0f
            handle.translationY = 0f
            
            // Force layout to update bounds immediately
            handle.requestLayout()
        }
        
        // Calculate end handle position
        // End handle: line points up INTO the text line, circle at bottom
        // Line should span the entire text line height (from line top to line bottom)
        val endLine = layout.getLineForOffset(selectionEnd)
        val endX = layout.getPrimaryHorizontal(selectionEnd)
        val endLineTop = layout.getLineTop(endLine).toFloat()
        val endLineBottom = layout.getLineBottom(endLine).toFloat()
        val endLineHeight = endLineBottom - endLineTop
        
        endHandle?.let { handle ->
            // Set line height to match text line height
            handle.setLineHeight(endLineHeight)
            
            val handleX = (offsetX + endX - handle.pivotOffsetX).toInt()
            // Position so handle's line covers the text line (line top at text line top)
            // Handle structure: [line (lineHeight)] + [circle (radius*2)]
            // pivotOffsetY = 0 (top of handle)
            // We want: handleY + pivotOffsetY = text line top
            val handleY = (offsetY + endLineTop - handle.pivotOffsetY).toInt()
            
            // Check if handle is within visible bounds
            val handleScreenY = containerLocation[1] + handleY
            val handleBottomScreenY = handleScreenY + handle.totalHeight.toInt()
            val isVisible = handleBottomScreenY > boundsTop && handleScreenY < boundsBottom
            handle.visibility = if (isVisible) View.VISIBLE else View.GONE
            
            // Use layout params to position the handle
            val params = handle.layoutParams as? FrameLayout.LayoutParams
            if (params != null) {
                params.leftMargin = handleX
                params.topMargin = handleY
                handle.layoutParams = params
            }
            handle.translationX = 0f
            handle.translationY = 0f
        }
    }
    
    private fun handleDrag(isStart: Boolean, deltaX: Float, deltaY: Float) {
        val layout = textView.layout ?: return
        val container = overlayContainer ?: return
        
        // Get the handle
        val handle = if (isStart) startHandle else endHandle
        handle ?: return
        
        // Move handle directly with finger using translation
        handle.translationX += deltaX
        handle.translationY += deltaY
        
        // Get handle's base position from layout params
        val params = handle.layoutParams as? FrameLayout.LayoutParams
        val baseX = params?.leftMargin ?: 0
        val baseY = params?.topMargin ?: 0
        
        // Calculate the actual position of handle tip (considering translation)
        val handleTipX = baseX + handle.translationX + handle.pivotOffsetX
        val handleTipY = baseY + handle.translationY  // Top of handle
        
        // Notify callback of handle position (for auto-scroll in TextPreviewActivity)
        val containerLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)
        val screenY = containerLocation[1] + handleTipY + handle.totalHeight
        handleDragCallback?.onHandleDrag(screenY, isStart)
        
        // Convert to textView local coordinates
        val textViewLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        
        val offsetX = textViewLocation[0] - containerLocation[0]
        val offsetY = textViewLocation[1] - containerLocation[1]
        
        val localX = handleTipX - offsetX - textView.paddingLeft
        val localY = handleTipY - offsetY - textView.paddingTop
        
        // Find the character offset at this position
        val offset = getOffsetForPosition(layout, localX, localY)
        
        // Update selection (without updating handle positions - they follow the finger)
        if (isStart) {
            if (offset < selectionEnd) {
                selectionStart = offset
                updateHighlight()  // Only update highlight, not handle positions
            }
        } else {
            if (offset > selectionStart) {
                selectionEnd = offset
                updateHighlight()  // Only update highlight, not handle positions
            }
        }
    }
    
    /**
     * Get character offset for a position in textView coordinates.
     */
    private fun getOffsetForPosition(layout: Layout, x: Float, y: Float): Int {
        val line = getLineForVertical(layout, y.toInt())
        return layout.getOffsetForHorizontal(line, x)
    }
    
    /**
     * Get line index for a vertical position.
     */
    private fun getLineForVertical(layout: Layout, y: Int): Int {
        val lineCount = layout.lineCount
        for (i in 0 until lineCount) {
            if (y < layout.getLineBottom(i)) {
                return i
            }
        }
        return lineCount - 1
    }
    
    private fun notifySelectionChanged() {
        onSelectionChanged?.onSelectionChanged(
            selectionStart, 
            selectionEnd, 
            isFullSelection()
        )
    }
}
