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
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.message.NoticeAggregator
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.databinding.ActivityTextPreviewBinding
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.chat.ui.messageaction.TextSelectionManager
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import com.difft.android.chat.util.Util
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

    @Inject
    lateinit var activityNoticeDispatcher: com.difft.android.chat.message.ActivityNoticeDispatcher

    private val binding: ActivityTextPreviewBinding by viewbind()
    private var fullText: String = ""
    private var mentions: List<Mention>? = null
    private var forwardContext: ForwardContext? = null

    // Source-message context for emitting copy/forward notices to the originating conversation.
    private var sourceAuthorId: String? = null
    private var sourceConversation: difft.android.messageserialization.For? = null

    // PRD v1.0 §5.3 combined-forward mode of the source message. UNKNOWN for main-conv messages
    // (Phase 4); Phase 5 will set SUB_COMBINED_FORWARD when launched from a CF detail view.
    private var sourceCombinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN

    // PRD v2.0 §改动2: whether the source message carries another person's ORIGINAL content (computed
    // by the launcher via NoticeAggregator). Text preview always copies/forwards REAL text, so the
    // trigger is by original author — single-forward → its inner author, not the forwarder.
    private var sourceCarriesForeign: Boolean = false
    
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
    // True for the lifetime of a gesture whose ACTION_DOWN landed on the floating close button.
    // Such gestures bypass all custom selection handling so the button behaves like a normal view.
    private var touchStartedOnCloseButton = false
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

        sourceAuthorId = intent.getStringExtra(EXTRA_SOURCE_AUTHOR_ID)
        val convId = intent.getStringExtra(EXTRA_SOURCE_CONVERSATION_ID)
        sourceConversation = convId?.let {
            when (intent.getIntExtra(EXTRA_SOURCE_CONVERSATION_TYPE, 0)) {
                1 -> difft.android.messageserialization.For.Group(it)
                else -> difft.android.messageserialization.For.Account(it)
            }
        }
        sourceCarriesForeign = intent.getBooleanExtra(EXTRA_SOURCE_CARRIES_FOREIGN, false)
        sourceCombinedForwardMode = intent.getStringExtra(EXTRA_SOURCE_COMBINED_FORWARD_MODE)
            ?.let { runCatching { CombinedForwardMode.valueOf(it) }.getOrNull() }
            ?: CombinedForwardMode.UNKNOWN

        setupTextView()
        setupOverlay()

        // Floating close button: dispatchTouchEvent routes gestures starting on it straight to
        // super (see touchStartedOnCloseButton), so this click fires without disturbing selection.
        binding.cvClose.setOnClickListener { finish() }
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
        
        // Add the selection overlay to our own layout root, inserted just *below* the floating
        // close button, so the selection highlight / drag handles never paint over the button.
        // (Adding it to android.R.id.content would stack the whole overlay above this layout —
        // including the button — hiding the button during selection.) Selection geometry uses
        // screen coordinates, so re-parenting here does not affect handle/highlight positioning.
        val root = binding.root as ViewGroup
        root.addView(overlayContainer, root.indexOfChild(binding.cvClose))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Hand the entire gesture to the normal view hierarchy when it starts on the close button,
        // so its click (and press feedback) work and none of the long-press / drag-to-select logic
        // below ever runs for it. Gestures starting elsewhere are unaffected, so dragging a text
        // selection across the button does not trigger it.
        // Use actionMasked so pointer-index bits in multi-touch events don't defeat the comparisons.
        val maskedAction = ev.actionMasked
        if (maskedAction == MotionEvent.ACTION_DOWN) {
            touchStartedOnCloseButton = isTouchOnCloseButton(ev)
            // Drop any long-press left posted by a prior gesture whose terminal event was missed,
            // so it can't fire selection UI while we are routing the close tap.
            if (touchStartedOnCloseButton) cancelLongPress()
        }
        if (touchStartedOnCloseButton) {
            if (maskedAction == MotionEvent.ACTION_UP || maskedAction == MotionEvent.ACTION_CANCEL) {
                touchStartedOnCloseButton = false
            }
            return super.dispatchTouchEvent(ev)
        }

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
    
    private fun isTouchOnCloseButton(ev: MotionEvent): Boolean {
        val button = binding.cvClose
        if (button.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        button.getLocationOnScreen(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + button.width,
            location[1] + button.height
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
        if (text.isEmpty()) {
            dismissSelection()
            return
        }
        Util.copyToClipboard(this, text)
        // PRD §4.1: emit copy notice on successful clipboard write (count=1).
        // PRD §5.3: mode is the source message's mode (UNKNOWN in Phase 4 main-conv,
        // SUB_COMBINED_FORWARD in Phase 5 CF-detail launches).
        val author = sourceAuthorId
        val conv = sourceConversation
        // PRD v2.0 §改动2: partial-text copy is real content; trace only if it belongs to someone
        // else, judged by the ORIGINAL author (single-forward → inner author), computed by the launcher.
        if (!author.isNullOrEmpty() && conv != null && sourceCarriesForeign) {
            activityNoticeDispatcher.dispatchCopyNotice(
                sourceConversation = conv,
                sourceAuthorIds = listOf(author),
                myId = globalServices.myId,
                messageCount = 1,
                combinedForwardMode = sourceCombinedForwardMode,
            )
        }
        dismissSelection()
    }

    private fun forwardSelectedText() {
        val text = textSelectionManager?.getSelectedText() ?: return
        val isFullSelect = textSelectionManager?.isFullSelection() == true
        // Both full-select and partial-text paths emit FORWARD_NOTICE (PRD §5).
        // Mode mirrors the source message's mode (Phase 5 sets SUB_COMBINED_FORWARD when
        // launched from CF detail; default UNKNOWN for main-conv launches).
        val authorIds = sourceAuthorId?.let { listOf(it) }
        // PRD v2.0 §改动2: trace by ORIGINAL author (single-forward → inner author), computed by the launcher.
        val carriesForeignContent = sourceCarriesForeign

        if (isFullSelect && forwardContext != null) {
            selectChatsUtils.showChatSelectAndSendDialog(
                this,
                text,
                null,
                null,
                listOf(forwardContext!!),
                sourceConversation = sourceConversation,
                sourceAuthorIds = authorIds,
                combinedForwardMode = sourceCombinedForwardMode,
                carriesForeignContent = carriesForeignContent,
            )
        } else {
            selectChatsUtils.showChatSelectAndSendDialog(
                this,
                text,
                sourceConversation = sourceConversation,
                sourceAuthorIds = authorIds,
                combinedForwardMode = sourceCombinedForwardMode,
                carriesForeignContent = carriesForeignContent,
            )
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
            (container.parent as? ViewGroup)?.removeView(container)
        }
        overlayContainer = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_MENTIONS = "extra_mentions"
        private const val EXTRA_FORWARD_CONTEXT = "extra_forward_context"
        private const val EXTRA_SOURCE_AUTHOR_ID = "extra_source_author_id"
        private const val EXTRA_SOURCE_CONVERSATION_ID = "extra_source_conversation_id"
        private const val EXTRA_SOURCE_CONVERSATION_TYPE = "extra_source_conversation_type"
        // Wire as enum.name String — CombinedForwardMode is not Parcelable.
        private const val EXTRA_SOURCE_COMBINED_FORWARD_MODE = "extra_source_combined_forward_mode"
        // PRD v2.0 §改动2: precomputed "carries another person's original content" flag (original-author based).
        private const val EXTRA_SOURCE_CARRIES_FOREIGN = "extra_source_carries_foreign"
        private const val SHORT_TEXT_THRESHOLD = 200

        /** @param sourceMessage Optional source message; when null, no copy/forward notice fires. */
        fun start(
            context: Context,
            text: String,
            mentions: List<Mention>? = null,
            forwardContext: ForwardContext? = null,
            sourceMessage: com.difft.android.chat.message.TextChatMessage? = null,
        ) {
            val intent = Intent(context, TextPreviewActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                mentions?.let { putExtra(EXTRA_MENTIONS, ArrayList(it)) }
                forwardContext?.let { putExtra(EXTRA_FORWARD_CONTEXT, it) }
                sourceMessage?.let { msg ->
                    val conv = msg.forWhat
                    if (conv != null) {
                        // PRD §5 / Phase 5: sourceAuthorOverride is set on inner CF sub-messages
                        // so the outer CF-sender is reported as the notice author. Phase 4
                        // main-conv messages have override=null → fall back to authorId.
                        putExtra(EXTRA_SOURCE_AUTHOR_ID, msg.sourceAuthorOverride ?: msg.authorId)
                        putExtra(EXTRA_SOURCE_CONVERSATION_ID, conv.id)
                        putExtra(EXTRA_SOURCE_CONVERSATION_TYPE, conv.typeValue)
                        // sourceMode null on main-conv messages → UNKNOWN over the wire.
                        putExtra(
                            EXTRA_SOURCE_COMBINED_FORWARD_MODE,
                            (msg.sourceMode ?: CombinedForwardMode.UNKNOWN).name,
                        )
                        // PRD v2.0 §改动2: text preview always copies/forwards REAL text → judge the
                        // trigger by ORIGINAL author (single-forward → inner author), consistent with
                        // the main copy/forward paths (the bubble author still feeds `from`).
                        // One flag gates both copy and forward here: TextPreviewActivity only opens for
                        // text/long-text (non-CF) messages, and for non-CF the copy and forward gating
                        // coincide (copyCarriesForeignContent == forwardCarriesForeignContent). They only
                        // diverge for a combined-forward (copy = placeholder/false, forward = recurse),
                        // which cannot reach this screen. Revisit (split into copy/forward flags) if a CF
                        // ever opens here.
                        putExtra(
                            EXTRA_SOURCE_CARRIES_FOREIGN,
                            NoticeAggregator.forwardCarriesForeignContent(listOf(msg), globalServices.myId),
                        )
                    }
                }
            }
            context.startActivity(intent)
        }
    }
}
