package com.difft.android.chat.ui.textpreview

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.difft.android.base.BaseActivity
import com.difft.android.chat.R
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.databinding.ActivityTextPreviewBinding
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.chat.ui.messageaction.TextSelectionManager
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import org.thoughtcrime.securesms.util.Util
import javax.inject.Inject
import kotlin.math.abs

/**
 * Full-screen text preview activity with custom text selection.
 * Uses custom TextSelectionManager for consistent selection UI across all devices.
 */
@AndroidEntryPoint
class TextPreviewActivity : BaseActivity() {

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    private val binding: ActivityTextPreviewBinding by viewbind()
    private var fullText: String = ""
    private var mentions: List<Mention>? = null
    private var forwardContext: ForwardContext? = null
    
    // Custom text selection
    private var textSelectionManager: TextSelectionManager? = null
    private var selectionPopup: TextPreviewSelectionPopup? = null
    private var overlayContainer: FrameLayout? = null
    
    // Long press detection and drag-to-select
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var isLongPressTriggered = false
    private var isDraggingSelection = false
    private var selectionAnchor = 0  // Fixed point during drag-to-select
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private val clickTimeout = 200L
    
    // Auto-scroll when dragging handle to edge
    private var autoScrollRunnable: Runnable? = null
    private val autoScrollSpeed = 15  // pixels per frame
    private val edgeThreshold = 100  // pixels from edge to trigger auto-scroll
    private var currentDragHandleY = 0f  // Current Y position of dragging handle
    private var currentDragIsStart = false  // Whether dragging start or end handle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fullText = intent.getStringExtra(EXTRA_TEXT) ?: ""
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        mentions = intent.getSerializableExtra(EXTRA_MENTIONS) as? ArrayList<Mention>
        @Suppress("DEPRECATION")
        forwardContext = intent.getSerializableExtra(EXTRA_FORWARD_CONTEXT) as? ForwardContext

        setupTextView()
        setupOverlay()
    }

    private fun setupTextView() {
        // Disable auto link detection (LinkTextUtils handles it)
        binding.textContent.autoLinkMask = 0
        
        // Disable system text selection - we use custom selection
        binding.textContent.setTextIsSelectable(false)

        // Use LinkTextUtils to handle links and mentions
        LinkTextUtils.setMarkdownToTextview(this, fullText, binding.textContent, mentions)

        // Adjust text alignment based on character count
        val isShortText = fullText.length <= SHORT_TEXT_THRESHOLD
        binding.textContent.gravity = if (isShortText) {
            android.view.Gravity.CENTER_HORIZONTAL
        } else {
            android.view.Gravity.START
        }
    }
    
    private fun setupOverlay() {
        // Create overlay container for selection UI
        overlayContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        // Add overlay to root view
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(overlayContainer)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                touchDownTime = System.currentTimeMillis()
                isLongPressTriggered = false
                isDraggingSelection = false
                
                // Check if touch is on text view
                if (isTouchOnTextView(ev)) {
                    // Start long press detection
                    longPressRunnable = Runnable {
                        if (!isLongPressTriggered) {
                            isLongPressTriggered = true
                            isDraggingSelection = true
                            onLongPress(ev.rawX, ev.rawY)
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, longPressTimeout)
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingSelection) {
                    // Continue drag-to-select
                    onDragSelection(ev.rawX, ev.rawY)
                    return true
                }
                
                val xDiff = abs(ev.rawX - touchDownX)
                val yDiff = abs(ev.rawY - touchDownY)
                
                // Cancel long press if moved too much (before long press triggered)
                if (xDiff > touchSlop || yDiff > touchSlop) {
                    cancelLongPress()
                    
                    // Dismiss selection on scroll (if not dragging handle)
                    // This handles the case where user lifts finger then scrolls
                    if (hasSelection() && !isHandleDragging()) {
                        dismissSelection()
                    }
                }
            }
            
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                
                if (isDraggingSelection) {
                    // Finish drag-to-select
                    isDraggingSelection = false
                    onDragSelectionEnd()
                    return true
                }
                
                // If long press was triggered, don't handle as click
                if (isLongPressTriggered) {
                    isLongPressTriggered = false
                    return true
                }
                
                val timeDiff = System.currentTimeMillis() - touchDownTime
                val xDiff = abs(ev.rawX - touchDownX)
                val yDiff = abs(ev.rawY - touchDownY)
                
                // Single click detection
                val isSingleClick = timeDiff < clickTimeout && xDiff < touchSlop && yDiff < touchSlop
                
                // Handle single click - only if not on popup or interactive elements
                if (isSingleClick) {
                    // Check if click is on popup area - let popup handle its own clicks
                    if (isTouchOnPopup(ev)) {
                        return super.dispatchTouchEvent(ev)
                    }
                    
                    // Check if click is on a clickable span (link, mention, etc.)
                    if (isTouchOnClickableSpan(ev)) {
                        return super.dispatchTouchEvent(ev)
                    }
                    
                    // Click outside popup and not on clickable span
                    if (hasSelection()) {
                        // Dismiss selection on single click
                        dismissSelection()
                        return true
                    } else {
                        // No selection - close activity
                        finish()
                        return true
                    }
                }
            }
            
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                isDraggingSelection = false
            }
        }
        
        return super.dispatchTouchEvent(ev)
    }
    
    private fun isTouchOnTextView(ev: MotionEvent): Boolean {
        val location = IntArray(2)
        binding.textContent.getLocationOnScreen(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + binding.textContent.width,
            location[1] + binding.textContent.height
        )
        return rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }
    
    private fun isTouchOnPopup(ev: MotionEvent): Boolean {
        val bounds = selectionPopup?.getPopupBounds() ?: return false
        return bounds.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }
    
    private fun isTouchOnClickableSpan(ev: MotionEvent): Boolean {
        val textView = binding.textContent
        val text = textView.text as? android.text.Spannable ?: return false
        
        // Convert screen coordinates to TextView local coordinates
        val location = IntArray(2)
        textView.getLocationOnScreen(location)
        val localX = ev.rawX - location[0]
        val localY = ev.rawY - location[1]
        
        // Adjust for padding and scroll
        val x = localX - textView.totalPaddingLeft + textView.scrollX
        val y = localY - textView.totalPaddingTop + textView.scrollY
        
        val layout = textView.layout ?: return false
        
        // Check if touch is within text bounds
        if (y < 0 || y > layout.height) return false
        
        val line = layout.getLineForVertical(y.toInt())
        val offset = layout.getOffsetForHorizontal(line, x)
        
        // Check if there's a ClickableSpan at this offset
        val clickableSpans = text.getSpans(offset, offset, android.text.style.ClickableSpan::class.java)
        return clickableSpans.isNotEmpty()
    }
    
    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }
    
    private fun onLongPress(x: Float, y: Float) {
        // Haptic feedback for long press
        binding.textContent.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        
        // Dismiss any existing selection
        dismissSelection()
        
        val container = overlayContainer ?: return
        
        // Create TextSelectionManager
        textSelectionManager = TextSelectionManager(binding.textContent).apply {
            onSelectionChanged = object : TextSelectionManager.SelectionCallback {
                override fun onSelectionChanged(start: Int, end: Int, isFullSelect: Boolean) {
                    // Only show popup when not dragging (will show on drag end)
                    if (!isDraggingSelection && start < end) {
                        showSelectionPopup()
                    } else if (start >= end) {
                        dismissSelectionPopup()
                    }
                }
            }
            handleDragCallback = object : TextSelectionManager.HandleDragCallback {
                override fun onHandleDrag(screenY: Float, isStart: Boolean) {
                    handleAutoScroll(screenY, isStart)
                }
                
                override fun onHandleDragEnd() {
                    stopAutoScroll()
                }
            }
            attachToOverlay(container)
            
            // Calculate character offset at touch position
            val offset = getCharacterOffsetAt(x, y)
            if (offset >= 0) {
                selectWordAt(offset)
                // Save anchor for drag-to-select (use selection start as anchor)
                selectionAnchor = getSelectionStart()
            } else {
                selectAll()
                selectionAnchor = 0
            }
        }
    }
    
    private var lastDragX = 0f
    private var lastDragY = 0f
    
    private fun onDragSelection(x: Float, y: Float) {
        lastDragX = x
        lastDragY = y
        
        val manager = textSelectionManager ?: return
        manager.extendSelectionToScreenPosition(x, y, selectionAnchor)
        
        // Check for auto-scroll during long-press drag
        handleAutoScrollDuringDrag(y)
    }
    
    private fun handleAutoScrollDuringDrag(screenY: Float) {
        // Save current position for use during scroll
        currentDragHandleY = screenY
        currentDragIsStart = false  // Always extend end during drag
        
        val scrollView = binding.rootLayout
        val scrollViewLocation = IntArray(2)
        scrollView.getLocationOnScreen(scrollViewLocation)
        
        val scrollViewTop = scrollViewLocation[1]
        val scrollViewBottom = scrollViewTop + scrollView.height
        
        val distanceFromTop = screenY - scrollViewTop
        val distanceFromBottom = scrollViewBottom - screenY
        
        val contentHeight = scrollView.getChildAt(0)?.height ?: 0
        val maxScroll = contentHeight - scrollView.height
        
        when {
            distanceFromTop < edgeThreshold && scrollView.scrollY > 0 -> {
                startAutoScrollDuringDrag(-autoScrollSpeed)
            }
            distanceFromBottom < edgeThreshold && scrollView.scrollY < maxScroll -> {
                startAutoScrollDuringDrag(autoScrollSpeed)
            }
            else -> {
                stopAutoScroll()
            }
        }
    }
    
    private fun startAutoScrollDuringDrag(scrollAmount: Int) {
        if (autoScrollRunnable != null) return
        
        autoScrollRunnable = object : Runnable {
            override fun run() {
                if (!isDraggingSelection) {
                    stopAutoScroll()
                    return
                }
                
                val scrollView = binding.rootLayout
                val currentScroll = scrollView.scrollY
                val maxScroll = scrollView.getChildAt(0).height - scrollView.height
                val newScroll = (currentScroll + scrollAmount).coerceIn(0, maxScroll)
                
                if (newScroll != currentScroll) {
                    scrollView.scrollTo(0, newScroll)
                    
                    // Update highlight position
                    textSelectionManager?.updateUIAfterScroll()
                    
                    // Update selection based on finger position
                    textSelectionManager?.extendSelectionToScreenPosition(
                        lastDragX,
                        lastDragY,
                        selectionAnchor
                    )
                    
                    handler.postDelayed(this, 16)
                } else {
                    stopAutoScroll()
                }
            }
        }
        handler.post(autoScrollRunnable!!)
    }
    
    private fun onDragSelectionEnd() {
        // Stop auto-scroll
        stopAutoScroll()
        
        // Show popup after drag ends
        if (hasSelection()) {
            showSelectionPopup()
        }
    }
    
    private fun getCharacterOffsetAt(screenX: Float, screenY: Float): Int {
        val layout = binding.textContent.layout ?: return -1
        
        // Get text view location
        val location = IntArray(2)
        binding.textContent.getLocationOnScreen(location)
        
        // Convert to text view local coordinates
        val localX = screenX - location[0] - binding.textContent.paddingLeft
        val localY = screenY - location[1] - binding.textContent.paddingTop
        
        // Account for vertical centering (gravity)
        val layoutHeight = layout.height
        val contentHeight = binding.textContent.height - binding.textContent.paddingTop - binding.textContent.paddingBottom
        val gravityOffset = if (contentHeight > layoutHeight) (contentHeight - layoutHeight) / 2 else 0
        val adjustedY = localY - gravityOffset
        
        // Find line at Y position
        val line = when {
            adjustedY < 0 -> 0
            adjustedY >= layout.height -> layout.lineCount - 1
            else -> layout.getLineForVertical(adjustedY.toInt())
        }
        
        // Find character offset at X position on this line
        return layout.getOffsetForHorizontal(line, localX)
    }
    
    private fun showSelectionPopup() {
        val manager = textSelectionManager ?: return
        val selectionBounds = manager.getSelectionBoundsOnScreen() ?: return
        val isFullSelection = manager.isFullSelection()
        
        // Always dismiss and recreate for simplicity
        dismissSelectionPopup()
        
        selectionPopup = TextPreviewSelectionPopup(this).apply {
            show(
                selectionBounds = selectionBounds,
                isFullSelection = isFullSelection,
                callbacks = object : TextPreviewSelectionPopup.Callbacks {
                    override fun onCopy() {
                        copySelectedText()
                    }
                    
                    override fun onForward() {
                        forwardSelectedText()
                    }
                    
                    override fun onTranslate() {
                        translateSelectedText()
                    }
                    
                    override fun onSelectAll() {
                        textSelectionManager?.selectAll()
                    }
                    
                    override fun onDismiss() {
                        // Popup dismissed externally
                    }
                }
            )
        }
    }
    
    private fun dismissSelectionPopup() {
        selectionPopup?.dismiss()
        selectionPopup = null
    }
    
    private fun dismissSelection() {
        dismissSelectionPopup()
        textSelectionManager?.detach()
        textSelectionManager = null
    }
    
    private fun hasSelection(): Boolean {
        return textSelectionManager?.hasSelection() == true
    }
    
    private fun isHandleDragging(): Boolean {
        return textSelectionManager?.isDragging() == true
    }
    
    private fun handleAutoScroll(handleScreenY: Float, isStart: Boolean) {
        // Save current handle position for use during scroll
        currentDragHandleY = handleScreenY
        currentDragIsStart = isStart
        
        val scrollView = binding.rootLayout
        val scrollViewLocation = IntArray(2)
        scrollView.getLocationOnScreen(scrollViewLocation)
        
        val scrollViewTop = scrollViewLocation[1]
        val scrollViewBottom = scrollViewTop + scrollView.height
        
        // Check if handle is near top or bottom edge
        val distanceFromTop = handleScreenY - scrollViewTop
        val distanceFromBottom = scrollViewBottom - handleScreenY
        
        val contentHeight = scrollView.getChildAt(0)?.height ?: 0
        val maxScroll = contentHeight - scrollView.height
        
        when {
            distanceFromTop < edgeThreshold && scrollView.scrollY > 0 -> {
                // Near top edge, scroll up
                startAutoScroll(-autoScrollSpeed)
            }
            distanceFromBottom < edgeThreshold && scrollView.scrollY < maxScroll -> {
                // Near bottom edge, scroll down
                startAutoScroll(autoScrollSpeed)
            }
            else -> {
                // Not near edge, stop auto-scroll
                stopAutoScroll()
            }
        }
    }
    
    private fun startAutoScroll(scrollAmount: Int) {
        // If already scrolling in the same direction, don't restart
        if (autoScrollRunnable != null) {
            return
        }
        
        autoScrollRunnable = object : Runnable {
            override fun run() {
                val scrollView = binding.rootLayout
                val currentScroll = scrollView.scrollY
                val maxScroll = scrollView.getChildAt(0).height - scrollView.height
                val newScroll = (currentScroll + scrollAmount).coerceIn(0, maxScroll)
                
                if (newScroll != currentScroll) {
                    scrollView.scrollTo(0, newScroll)
                    
                    // Update highlight position after scroll
                    textSelectionManager?.updateUIAfterScroll()
                    
                    // Update selection based on handle's screen position
                    // Since textView moved but handle stayed at same screen position,
                    // we need to find the new character at that position
                    updateSelectionDuringScroll()
                    
                    // Continue scrolling
                    handler.postDelayed(this, 16) // ~60fps
                } else {
                    stopAutoScroll()
                }
            }
        }
        handler.post(autoScrollRunnable!!)
    }
    
    private fun updateSelectionDuringScroll() {
        val manager = textSelectionManager ?: return
        
        // Calculate text position from handle's screen position
        val textView = binding.textContent
        val textViewLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)
        
        // Approximate X position (use center of text view)
        val screenX = textViewLocation[0] + textView.width / 2f
        val screenY = currentDragHandleY
        
        // Get character offset at this position
        val offset = manager.getOffsetForScreenPosition(screenX, screenY)
        if (offset < 0) return
        
        // Update selection
        if (currentDragIsStart) {
            val currentEnd = manager.getSelectionEnd()
            if (offset < currentEnd) {
                manager.setSelection(offset, currentEnd)
            }
        } else {
            val currentStart = manager.getSelectionStart()
            if (offset > currentStart) {
                manager.setSelection(currentStart, offset)
            }
        }
    }
    
    private fun stopAutoScroll() {
        autoScrollRunnable?.let { handler.removeCallbacks(it) }
        autoScrollRunnable = null
    }
    
    private fun copySelectedText() {
        val text = textSelectionManager?.getSelectedText() ?: return
        Util.copyToClipboard(this, text)
        dismissSelection()
    }
    
    private fun forwardSelectedText() {
        val text = textSelectionManager?.getSelectedText() ?: return
        val isFullSelect = textSelectionManager?.isFullSelection() == true
        
        // If select all and has forward context, forward as original message
        if (isFullSelect && forwardContext != null) {
            selectChatsUtils.showChatSelectAndSendDialog(
                this,
                text,
                null,
                null,
                listOf(forwardContext!!)
            )
        } else {
            selectChatsUtils.showChatSelectAndSendDialog(this, text)
        }
        
        dismissSelection()
    }
    
    private fun translateSelectedText() {
        val text = textSelectionManager?.getSelectedText() ?: return
        TranslateBottomSheetFragment.show(this, text)
        dismissSelection()
    }

    override fun onDestroy() {
        cancelLongPress()
        dismissSelection()
        overlayContainer?.let { container ->
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            rootView?.removeView(container)
        }
        overlayContainer = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_MENTIONS = "extra_mentions"
        private const val EXTRA_FORWARD_CONTEXT = "extra_forward_context"
        private const val SHORT_TEXT_THRESHOLD = 200

        fun start(
            context: Context,
            text: String,
            mentions: List<Mention>? = null,
            forwardContext: ForwardContext? = null
        ) {
            val intent = Intent(context, TextPreviewActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                mentions?.let { putExtra(EXTRA_MENTIONS, ArrayList(it)) }
                forwardContext?.let { putExtra(EXTRA_FORWARD_CONTEXT, it) }
            }
            context.startActivity(intent)
        }
    }
}
