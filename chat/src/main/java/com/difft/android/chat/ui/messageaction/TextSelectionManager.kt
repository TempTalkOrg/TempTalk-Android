package com.difft.android.chat.ui.messageaction

import android.graphics.Point
import android.graphics.Rect
import android.text.Layout
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.hypot





















class TextSelectionManager(
    private val textView: TextView
) {
    
    


    interface SelectionCallback {
        fun onSelectionChanged(start: Int, end: Int, isFullSelect: Boolean)
    }
    
    




    interface HandleDragCallback {
        




        fun onHandleDrag(screenY: Float, isStart: Boolean)

        



        fun onHandleDragStarted(isStart: Boolean) {}

        


        fun onHandleDragEnd()
    }
    
    
    private var selectionStart = 0
    private var selectionEnd = 0

    
    
    private val textLength: Int get() = textView.text?.length ?: 0
    private val safeStart: Int get() = selectionStart.coerceIn(0, textLength)
    private val safeEnd: Int get() = selectionEnd.coerceIn(0, textLength)
    private var isEnabled = true
    private var isDragging = false
    private var dragStartNotified = false
    private val touchSlop = ViewConfiguration.get(textView.context).scaledTouchSlop
    
    
    private var overlayContainer: ViewGroup? = null
    private var highlightView: SelectionHighlightView? = null
    private var startHandle: SelectionHandleView? = null
    private var endHandle: SelectionHandleView? = null
    
    
    private var clipBoundsView: View? = null
    private val scrollLocation = IntArray(2)
    private val scrollBox = Rect()
    private val scrollClip = Rect()
    
    
    var onSelectionChanged: SelectionCallback? = null
    var handleDragCallback: HandleDragCallback? = null
    
    



    fun setEnabled(enabled: Boolean) {
        this.isEnabled = enabled
        if (!enabled) {
            clearSelection()
        }
    }
    
    


    fun isEnabled(): Boolean = isEnabled
    
    







    fun attachToOverlay(container: ViewGroup, clipBoundsView: View? = null) {
        if (!isEnabled) return
        
        this.overlayContainer = container
        this.clipBoundsView = clipBoundsView
        
        
        highlightView = SelectionHighlightView(textView.context).apply {
            bindToTextView(textView)
        }
        
        
        startHandle = SelectionHandleView(textView.context, isStart = true).apply {
            visibility = View.GONE
            onDragStart = ::onHandleTouchDragStart
            onDragEnd = ::onHandleTouchDragEnd
            onDrag = { deltaX, deltaY -> handleDrag(isStart = true, deltaX, deltaY) }
        }
        
        endHandle = SelectionHandleView(textView.context, isStart = false).apply {
            visibility = View.GONE
            onDragStart = ::onHandleTouchDragStart
            onDragEnd = ::onHandleTouchDragEnd
            onDrag = { deltaX, deltaY -> handleDrag(isStart = false, deltaX, deltaY) }
        }
        
        
        
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
        
        
        
        startHandle?.elevation = SELECTION_HANDLE_ELEVATION_PX
        endHandle?.elevation = SELECTION_HANDLE_ELEVATION_PX
        
        
        updateHighlightViewPosition()
    }
    
    


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
    
    



    fun transferToOverlay(newContainer: ViewGroup) {
        val oldContainer = overlayContainer ?: return
        
        
        highlightView?.let { oldContainer.removeView(it) }
        startHandle?.let { oldContainer.removeView(it) }
        endHandle?.let { oldContainer.removeView(it) }
        
        
        overlayContainer = newContainer
        
        highlightView?.let { newContainer.addView(it) }
        startHandle?.let { newContainer.addView(it) }
        endHandle?.let { newContainer.addView(it) }
        
        
        updateHighlightViewPosition()
        updateHandlePositions()
    }
    
    


    fun selectAll() {
        if (!isEnabled) return
        
        val text = textView.text ?: return
        setSelection(0, text.length)
    }
    
    





    fun setSelection(start: Int, end: Int) {
        if (!isEnabled) return
        
        val text = textView.text ?: return
        val textLength = text.length
        
        
        var newStart = start.coerceIn(0, textLength)
        var newEnd = end.coerceIn(0, textLength)
        
        
        if (newStart > newEnd) {
            val temp = newStart
            newStart = newEnd
            newEnd = temp
        }
        
        selectionStart = newStart
        selectionEnd = newEnd
        
        updateUI()
        
        
        if (!isDragging) {
            notifySelectionChanged()
        }
    }
    
    


    fun clearSelection() {
        selectionStart = 0
        selectionEnd = 0
        
        highlightView?.clearSelection()
        startHandle?.visibility = View.GONE
        endHandle?.visibility = View.GONE
    }
    
    


    fun getSelectedText(): String {
        val text = textView.text ?: return ""
        val start = safeStart
        val end = safeEnd
        return if (start < end) {
            text.subSequence(start, end).toString()
        } else {
            ""
        }
    }
    
    


    fun getSelectionStart(): Int = selectionStart
    
    


    fun getSelectionEnd(): Int = selectionEnd
    
    


    fun hasSelection(): Boolean = selectionStart < selectionEnd
    
    


    fun isFullSelection(): Boolean {
        val textLength = textView.text?.length ?: 0
        return selectionStart == 0 && selectionEnd == textLength && textLength > 0
    }
    
    


    fun isDragging(): Boolean = isDragging
    
    



    fun updateUIAfterScroll() {
        updateHighlightViewPosition()
        updateHighlight()
        
        if (!isDragging) {
            updateHandlePositions()
        }
    }
    
    



    fun onListScrolled() {
        val container = overlayContainer ?: return
        val highlight = highlightView ?: return
        container.getLocationOnScreen(scrollLocation)
        val (containerX, containerY) = scrollLocation
        textView.getLocationOnScreen(scrollLocation)
        val (textX, textY) = scrollLocation
        highlight.x = (textX - containerX).toFloat()
        highlight.y = (textY - containerY).toFloat()
        clipBoundsView?.let { boundsView ->
            
            highlight.clipBounds = boundsView.clipBoxOnScreen(scrollBox, scrollLocation).toHighlightClip(textX, textY, scrollClip)
        }
        if (!isDragging) updateHandlePositions()
    }

    
    private fun Rect.toHighlightClip(textX: Int, textY: Int, out: Rect): Rect {
        out.set(
            (left - textX).coerceAtLeast(0),
            (top - textY).coerceAtLeast(0),
            (right - textX).coerceAtMost(textView.width),
            (bottom - textY).coerceAtMost(textView.height),
        )
        return out
    }

    





    fun selectWordAt(offset: Int) {
        if (!isEnabled) return
        
        val text = textView.text?.toString() ?: return
        if (text.isEmpty()) return
        
        val clampedOffset = offset.coerceIn(0, text.length)
        
        
        var start = clampedOffset
        var end = clampedOffset
        
        
        while (start > 0 && !text[start - 1].isWhitespace()) {
            start--
        }
        
        
        while (end < text.length && !text[end].isWhitespace()) {
            end++
        }
        
        
        if (start == end) {
            
            if (start > 0) {
                end = start
                while (start > 0 && !text[start - 1].isWhitespace()) {
                    start--
                }
            }
            
            if (start == end && end < text.length) {
                start = end
                while (end < text.length && !text[end].isWhitespace()) {
                    end++
                }
            }
        }
        
        
        if (start < end) {
            setSelection(start, end)
        }
    }
    
    





    fun getSelectionBoundsOnScreen(): Rect? {
        if (!hasSelection()) return null
        
        val layout = textView.layout ?: return null
        val container = overlayContainer ?: return null
        val start = safeStart
        val end = safeEnd
        
        
        val textViewLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        
        val offsetX = textViewLocation[0] + textView.paddingLeft
        val offsetY = textViewLocation[1] + textView.paddingTop
        
        
        val startLine = layout.getLineForOffset(start)
        val startLineTop = layout.getLineTop(startLine)
        
        
        val endLine = layout.getLineForOffset(end)
        val endLineBottom = layout.getLineBottom(endLine)
        
        
        val startX = layout.getPrimaryHorizontal(start)
        val endX = layout.getPrimaryHorizontal(end)
        
        
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
    
    











    fun extendSelectionToScreenPosition(screenX: Float, screenY: Float, anchorStart: Int) {
        if (!isEnabled) return
        
        val offset = getOffsetForScreenPosition(screenX, screenY)
        if (offset < 0) return
        
        
        val newStart: Int
        val newEnd: Int
        if (offset < anchorStart) {
            newStart = offset
            newEnd = anchorStart
        } else {
            newStart = anchorStart
            newEnd = offset
        }
        
        
        if (newStart != selectionStart || newEnd != selectionEnd) {
            selectionStart = newStart
            selectionEnd = newEnd
            updateUI()
            
        }
    }
    
    






    fun getOffsetForScreenPosition(screenX: Float, screenY: Float): Int {
        val layout = textView.layout ?: return -1
        
        
        val textViewLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        
        
        val localX = screenX - textViewLocation[0] - textView.paddingLeft
        val localY = screenY - textViewLocation[1] - textView.paddingTop
        
        
        val line = when {
            localY < 0 -> 0
            localY >= layout.height -> layout.lineCount - 1
            else -> layout.getLineForVertical(localY.toInt())
        }
        
        
        return layout.getOffsetForHorizontal(line, localX)
    }
    
    
    
    
    
    private fun updateUI() {
        updateHighlightViewPosition()
        updateHighlight()
        updateHandlePositions()
    }
    
    private fun updateHighlightViewPosition() {
        val container = overlayContainer ?: return
        val highlight = highlightView ?: return
        
        
        val textViewLocation = IntArray(2)
        val containerLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        container.getLocationOnScreen(containerLocation)
        
        val offsetX = textViewLocation[0] - containerLocation[0]
        val offsetY = textViewLocation[1] - containerLocation[1]
        
        
        highlight.x = offsetX.toFloat()
        highlight.y = offsetY.toFloat()
        
        highlight.layoutParams?.let { params ->
            params.width = textView.width
            params.height = textView.height
            highlight.layoutParams = params
        }
        
        
        clipBoundsView?.let { boundsView ->
            highlight.clipBounds =
                boundsView.clipBoxOnScreen(Rect()).toHighlightClip(textViewLocation[0], textViewLocation[1], Rect())
        }
    }
    
    private fun updateHighlight() {
        highlightView?.setSelection(safeStart, safeEnd)
    }
    
    private fun updateHandlePositions() {
        val layout = textView.layout ?: return
        val container = overlayContainer ?: return
        
        if (!hasSelection()) {
            startHandle?.visibility = View.GONE
            endHandle?.visibility = View.GONE
            return
        }
        
        val origin = textView.contentOriginIn(container)
        val clip = VerticalClip.of(clipBoundsView, container)
        startHandle?.let { placeHandle(it, layout.edgeLineFor(safeStart), origin, clip, isStart = true) }
        endHandle?.let { placeHandle(it, layout.edgeLineFor(safeEnd), origin, clip, isStart = false) }
    }
    
    private fun onHandleTouchDragStart() {
        isDragging = true
        dragStartNotified = false
    }
    
    private fun onHandleTouchDragEnd() {
        isDragging = false
        dragStartNotified = false
        
        updateHandlePositions()
        notifySelectionChanged()
        handleDragCallback?.onHandleDragEnd()
    }
    
    





    private fun placeHandle(
        handle: SelectionHandleView,
        line: EdgeLine,
        origin: Point,
        clip: VerticalClip,
        isStart: Boolean
    ) {
        handle.setLineHeight(line.height.toFloat())
        
        val connectY = if (isStart) line.bottom else line.top
        val handleX = (origin.x + line.x - handle.pivotOffsetX).toInt()
        val handleY = (origin.y + connectY - handle.pivotOffsetY).toInt()
        val visualTop = handleY + handle.visualTopInset.toInt()
        val visualBottom = visualTop + handle.visualExtentPx.toInt()
        val isVisible = clip.intersects(visualTop, visualBottom)
        handle.visibility = if (isVisible) View.VISIBLE else View.GONE
        
        handle.clipBounds = if (isVisible && (visualTop < clip.top || visualBottom > clip.bottom)) {
            Rect(0, clip.top - handleY, handle.totalWidth.toInt(), clip.bottom - handleY)
        } else {
            null
        }

        
        (handle.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            if (params.leftMargin != handleX || params.topMargin != handleY) {
                params.leftMargin = handleX
                params.topMargin = handleY
                handle.layoutParams = params
            }
        }
        
        handle.translationX = 0f
        handle.translationY = 0f
    }
    
    





    fun getVisibleHandleRowRects(): SelectionRowRects {
        val layout = textView.layout ?: return SelectionRowRects.NONE
        val container = overlayContainer ?: return SelectionRowRects.NONE
        if (!hasSelection() || isDragging || !textView.isShown) return SelectionRowRects.NONE
        
        val origin = textView.contentOriginIn(container)
        val width = textView.width - textView.paddingLeft - textView.paddingRight
        fun row(handle: SelectionHandleView?, offset: Int, isStart: Boolean): Rect? =
            handle?.takeIf { it.visibility == View.VISIBLE }?.let {
                layout.edgeLineFor(offset).toRowRect(origin, width)
                    .withHandleKnob(it.visualExtentPx.toInt(), isStart)
            }
        return SelectionRowRects(
            start = row(startHandle, safeStart, isStart = true),
            end = row(endHandle, safeEnd, isStart = false)
        )
    }
    
    private fun handleDrag(isStart: Boolean, deltaX: Float, deltaY: Float) {
        val layout = textView.layout ?: return
        val container = overlayContainer ?: return
        
        
        val handle = if (isStart) startHandle else endHandle
        handle ?: return
        
        
        handle.translationX += deltaX
        handle.translationY += deltaY
        
        
        if (!dragStartNotified && hypot(handle.translationX, handle.translationY) > touchSlop) {
            dragStartNotified = true
            
            
            handle.clipBounds = null
            handleDragCallback?.onHandleDragStarted(isStart)
        }
        
        
        val params = handle.layoutParams as? FrameLayout.LayoutParams
        val baseX = params?.leftMargin ?: 0
        val baseY = params?.topMargin ?: 0
        
        
        val handleTipX = baseX + handle.translationX + handle.pivotOffsetX
        val handleTipY = baseY + handle.translationY  
        
        
        val containerLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)
        val screenY = containerLocation[1] + handleTipY + handle.totalHeight
        handleDragCallback?.onHandleDrag(screenY, isStart)
        
        
        val textViewLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        
        val offsetX = textViewLocation[0] - containerLocation[0]
        val offsetY = textViewLocation[1] - containerLocation[1]
        
        val localX = handleTipX - offsetX - textView.paddingLeft
        val localY = handleTipY - offsetY - textView.paddingTop
        
        
        val offset = getOffsetForPosition(layout, localX, localY)
        
        
        if (isStart) {
            if (offset < selectionEnd) {
                selectionStart = offset
                updateHighlight()  
            }
        } else {
            if (offset > selectionStart) {
                selectionEnd = offset
                updateHighlight()  
            }
        }
    }
    
    


    private fun getOffsetForPosition(layout: Layout, x: Float, y: Float): Int {
        val line = getLineForVertical(layout, y.toInt())
        return layout.getOffsetForHorizontal(line, x)
    }
    
    


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
