package com.difft.android.chat.ui.messageaction

import android.graphics.Point
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.common.TextTruncationUtil
import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.Mention

/**
 * Coordinates the message action popup and text selection menu interactions.
 * 
 * This class manages:
 * - Showing the message action popup on long press
 * - Enabling text selection for text messages
 * - Coordinating between action popup and text selection menu
 * - Handling action callbacks
 * 
 * Flow:
 * 1. Long press → Show message action popup + select all text
 * 2. User drags selection (not full) → Hide message action popup, show text selection menu
 * 3. User clicks "Select All" → Show message action popup again
 */
class MessageActionCoordinator(
    private val activity: FragmentActivity,
    private val globalConfigsManager: IGlobalConfigsManager
) {
    
    /**
     * Listener for message action events
     */
    interface ActionListener {
        /**
         * Called when reaction is selected
         * @param isRemove true if user wants to remove existing reaction, false to add new
         */
        fun onReactionSelected(message: TextChatMessage, emoji: String, isRemove: Boolean)
        fun onMoreEmojiClick(message: TextChatMessage)
        fun onQuote(message: TextChatMessage)
        fun onCopy(message: TextChatMessage, selectedText: String? = null)
        fun onTranslate(message: TextChatMessage, selectedText: String? = null)
        fun onTranslateOff(message: TextChatMessage)
        fun onForward(message: TextChatMessage, selectedText: String? = null)
        fun onSpeechToText(message: TextChatMessage)
        fun onSpeechToTextOff(message: TextChatMessage)
        fun onSave(message: TextChatMessage)
        fun onMultiSelect(message: TextChatMessage)
        fun onSaveToNote(message: TextChatMessage)
        fun onDeleteSaved(message: TextChatMessage)
        fun onRecall(message: TextChatMessage)
        fun onMoreInfo(message: TextChatMessage)
        fun onDismiss()
    }
    
    private val configBuilder = MessageActionConfigBuilder(globalConfigsManager)
    private var messageActionPopup: MessageActionPopup? = null
    private var textSelectionManager: TextSelectionManager? = null
    
    private var currentMessage: TextChatMessage? = null
    private var currentTextView: TextView? = null
    private var currentAnchorView: View? = null
    private var currentContainerView: View? = null
    private var currentConfig: MessageActionConfigBuilder.Config? = null
    private var currentRestoreWindowBackground: Boolean = true
    private var actionListener: ActionListener? = null
    private var lastSelStart = -1
    private var lastSelEnd = -1
    private var isInitializing = true  // Skip selection changes during initialization
    private var textSelectionEnabled = true  // Can be disabled for confidential messages
    // Save original text for restoring OnTouchListener (message.message may change due to RecyclerView recycling)
    private var savedRawText: String = ""
    private var savedMentions: List<Mention>? = null
    
    // Listeners to dismiss popup when list scrolls or data changes
    private var scrollListener: RecyclerView.OnScrollListener? = null
    private var adapterObserver: RecyclerView.AdapterDataObserver? = null
    private var currentRecyclerView: RecyclerView? = null
    
    /**
     * Set the action listener
     */
    fun setActionListener(listener: ActionListener) {
        this.actionListener = listener
    }
    
    /**
     * Show message action popup for a message
     * 
     * @param message The message to show actions for
     * @param messageView The view of the message bubble
     * @param textView Optional TextView for text selection (if message has text)
     * @param mostUseEmojis List of most used emojis
     * @param isForForward Whether in forward selection mode
     * @param isSaved Whether the message is saved
     * @param touchPoint The touch point in screen coordinates
     * @param containerView Optional container view for determining available bounds (e.g., RecyclerView)
     * @param restoreWindowBackground Whether to restore window background after showing popup (default true).
     *        Set to false when background is set on a view rather than window (e.g., in popup-style activities)
     * @param enableTextSelection Whether to enable text selection (default true).
     *        Set to false for confidential messages.
     */
    fun show(
        message: TextChatMessage,
        messageView: View,
        textView: TextView?,
        mostUseEmojis: List<String>?,
        isForForward: Boolean,
        isSaved: Boolean,
        touchPoint: Point,
        containerView: View? = null,
        restoreWindowBackground: Boolean = true,
        enableTextSelection: Boolean = true
    ) {
        // If popup is already showing for the same message, ignore (prevents duplicate overlay)
        if (isShowing && currentMessage?.id == message.id) {
            return
        }
        
        // Dismiss any existing
        dismiss()
        
        currentMessage = message
        currentTextView = textView
        currentAnchorView = messageView
        currentContainerView = containerView
        currentRestoreWindowBackground = restoreWindowBackground
        textSelectionEnabled = enableTextSelection
        // Save original text for restoring OnTouchListener later
        // For single forward messages, the text is in forwardContext.forwards[0].text
        val forwardsSize = message.forwardContext?.forwards?.size ?: 0
        if (forwardsSize == 1) {
            val forward = message.forwardContext?.forwards?.firstOrNull()
            savedRawText = forward?.text ?: ""
            savedMentions = forward?.mentions
        } else {
            savedRawText = message.message?.toString() ?: ""
            savedMentions = message.mentions
        }
        
        // Get message bubble bounds
        val bubbleBounds = Rect()
        messageView.getGlobalVisibleRect(bubbleBounds)
        
        // Build config and save for later use
        val config = configBuilder.build(
            message = message,
            mostUseEmojis = mostUseEmojis,
            isForForward = isForForward,
            isSaved = isSaved,
            anchorBounds = bubbleBounds
        )
        currentConfig = config
        
        // Create and show popup
        messageActionPopup = MessageActionPopup(activity).also { popup ->
            // Setup selection menu callbacks
            popup.setSelectionMenuCallbacks(object : MessageActionPopup.SelectionMenuCallbacks {
                override fun onCopy() {
                    val selectedText = textSelectionManager?.getSelectedText() ?: ""
                    currentMessage?.let { msg ->
                        actionListener?.onCopy(msg, selectedText)
                    }
                    dismiss()
                }
                
                override fun onForward() {
                    val selectedText = textSelectionManager?.getSelectedText() ?: ""
                    currentMessage?.let { msg ->
                        actionListener?.onForward(msg, selectedText)
                    }
                    dismiss()
                }
                
                override fun onSelectAll() {
                    // Select all text and switch back to full menu
                    textSelectionManager?.selectAll()
                    // Menu will switch automatically via handleCustomSelectionChanged
                }
            })
            
            popup.show(
                anchorView = messageView,
                config = config,
                containerView = containerView,
                restoreWindowBackground = restoreWindowBackground,
                textView = textView,
                callbacks = createPopupCallbacks()
            )
        }
        
        // Setup custom text selection if enabled and text view is provided
        isInitializing = true
        if (enableTextSelection && textView != null) {
            setupCustomTextSelection(textView)
        }
        
        // Clear initializing flag after a short delay
        textView?.postDelayed({
            isInitializing = false
        }, 100) ?: run {
            isInitializing = false
        }
        
        // Add scroll listener to dismiss popup when list scrolls
        setupScrollListener(containerView)
    }
    
    /**
     * Setup scroll listener on RecyclerView to dismiss popup when list scrolls or data changes
     */
    private fun setupScrollListener(containerView: View?) {
        // Remove any existing listener
        removeScrollListener()
        
        // Find RecyclerView - search up the view hierarchy from anchorView or containerView
        val recyclerView = findRecyclerView(currentAnchorView) 
            ?: findRecyclerView(containerView)
        
        if (recyclerView != null) {
            currentRecyclerView = recyclerView
            
            // Scroll listener - dismiss when user scrolls
            scrollListener = object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Dismiss on any scroll
                    if (dx != 0 || dy != 0) {
                        dismiss()
                    }
                }
            }
            recyclerView.addOnScrollListener(scrollListener!!)
            
            // Adapter observer - dismiss when data changes (e.g., new message received)
            adapterObserver = object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    dismiss()
                }
                
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    dismiss()
                }
                
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    dismiss()
                }
                
                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                    dismiss()
                }
            }
            recyclerView.adapter?.registerAdapterDataObserver(adapterObserver!!)
        }
    }
    
    /**
     * Find RecyclerView in the view hierarchy (searching up from the given view)
     */
    private fun findRecyclerView(view: View?): RecyclerView? {
        var current: View? = view
        while (current != null) {
            if (current is RecyclerView) {
                return current
            }
            val parent = current.parent
            current = if (parent is View) parent else null
        }
        return null
    }
    
    /**
     * Remove scroll listener and adapter observer from RecyclerView
     */
    private fun removeScrollListener() {
        scrollListener?.let { listener ->
            currentRecyclerView?.removeOnScrollListener(listener)
        }
        adapterObserver?.let { observer ->
            try {
                currentRecyclerView?.adapter?.unregisterAdapterDataObserver(observer)
            } catch (e: Exception) {
                // Observer might not be registered
                L.w { "[MessageActionCoordinator] unregisterAdapterDataObserver failed: ${e.stackTraceToString()}" }
            }
        }
        scrollListener = null
        adapterObserver = null
        currentRecyclerView = null
    }
    
    /**
     * Dismiss all popups and clear state
     */
    fun dismiss() {
        // Remove scroll listener first
        removeScrollListener()
        
        messageActionPopup?.dismiss()
        messageActionPopup = null
        
        // Clean up custom text selection
        textSelectionManager?.detach()
        textSelectionManager = null
        
        // Restore OnTouchListener for double-click preview using saved values
        val textView = currentTextView
        val message = currentMessage
        if (textView != null && message != null && savedRawText.isNotEmpty()) {
            TextTruncationUtil.setupDoubleClickPreview(textView, savedRawText, savedMentions, message)
        }
        
        currentTextView = null
        currentAnchorView = null
        currentMessage = null
        currentConfig = null
        lastSelStart = -1
        lastSelEnd = -1
        isInitializing = true
        savedRawText = ""
        savedMentions = null
    }
    
    /**
     * Check if any popup is currently showing
     */
    val isShowing: Boolean
        get() = messageActionPopup?.isShowing == true
    
    /**
     * Get current message
     */
    val message: TextChatMessage?
        get() = currentMessage
    
    private fun createPopupCallbacks(): MessageActionPopup.Callbacks {
        return object : MessageActionPopup.Callbacks {
            override fun onReactionSelected(emoji: String, isRemove: Boolean) {
                currentMessage?.let { msg ->
                    actionListener?.onReactionSelected(msg, emoji, isRemove)
                }
            }
            
            override fun onMoreEmojiClick() {
                currentMessage?.let { msg ->
                    actionListener?.onMoreEmojiClick(msg)
                }
            }
            
            override fun onActionSelected(action: MessageAction.Type) {
                handleAction(action)
            }
            
            override fun onDismiss() {
                cleanupAfterDismiss()
            }
        }
    }
    
    private fun handleAction(action: MessageAction.Type) {
        val message = currentMessage ?: return
        
        when (action) {
            MessageAction.Type.QUOTE -> actionListener?.onQuote(message)
            MessageAction.Type.COPY -> actionListener?.onCopy(message)
            MessageAction.Type.TRANSLATE -> actionListener?.onTranslate(message)
            MessageAction.Type.TRANSLATE_OFF -> actionListener?.onTranslateOff(message)
            MessageAction.Type.FORWARD -> actionListener?.onForward(message)
            MessageAction.Type.SPEECH_TO_TEXT -> actionListener?.onSpeechToText(message)
            MessageAction.Type.SPEECH_TO_TEXT_OFF -> actionListener?.onSpeechToTextOff(message)
            MessageAction.Type.SAVE -> actionListener?.onSave(message)
            MessageAction.Type.MULTISELECT -> actionListener?.onMultiSelect(message)
            MessageAction.Type.SAVE_TO_NOTE -> actionListener?.onSaveToNote(message)
            MessageAction.Type.DELETE_SAVED -> actionListener?.onDeleteSaved(message)
            MessageAction.Type.RECALL -> actionListener?.onRecall(message)
            MessageAction.Type.MORE_INFO -> actionListener?.onMoreInfo(message)
            MessageAction.Type.MORE -> { /* Handled by popup internally */ }
            MessageAction.Type.SELECT_ALL -> { /* Handled by TextSelectionPopup */ }
            MessageAction.Type.RESEND,
            MessageAction.Type.DELETE -> { /* Handled by FailedMessageActionPopup */ }
        }
    }
    
    /**
     * Setup custom text selection using TextSelectionManager.
     * This replaces the system's textIsSelectable with a fully custom implementation.
     */
    private fun setupCustomTextSelection(textView: TextView) {
        // Wait for popup to be ready before attaching selection manager
        textView.post {
            val overlayContainer = messageActionPopup?.getOverlayContainer()
            if (overlayContainer == null) {
                return@post
            }
            
            // Create and setup TextSelectionManager
            textSelectionManager = TextSelectionManager(textView).apply {
                setEnabled(textSelectionEnabled)
                onSelectionChanged = object : TextSelectionManager.SelectionCallback {
                    override fun onSelectionChanged(start: Int, end: Int, isFullSelect: Boolean) {
                        handleCustomSelectionChanged(start, end, isFullSelect)
                    }
                }
                // Pass containerView (e.g., RecyclerView) to clip highlight bounds
                attachToOverlay(overlayContainer, currentContainerView)
                
                // Select all text initially
                selectAll()
            }
        }
    }
    
    /**
     * Handle selection changes from TextSelectionManager.
     * This is called when user finishes dragging a selection handle.
     */
    private fun handleCustomSelectionChanged(selStart: Int, selEnd: Int, isFullSelect: Boolean) {
        // Skip during initialization
        if (isInitializing) {
            return
        }
        
        val hasSelection = selStart < selEnd
        
        lastSelStart = selStart
        lastSelEnd = selEnd
        
        if (!hasSelection || isFullSelect) {
            // Full selection or no selection - switch to full menu mode
            messageActionPopup?.showFullMenu()
            return
        }
        
        // Partial selection - switch to selection menu mode (Copy, Forward, SelectAll)
        messageActionPopup?.showSelectionMenu()
    }
    
    
    private fun cleanupAfterDismiss() {
        // Clean up custom text selection
        textSelectionManager?.detach()
        textSelectionManager = null
        
        // Restore OnTouchListener for double-click preview using saved values
        // Skip if already cleaned up by dismiss() (savedRawText would be empty)
        val textView = currentTextView
        val message = currentMessage
        if (textView != null && message != null && savedRawText.isNotEmpty()) {
            TextTruncationUtil.setupDoubleClickPreview(textView, savedRawText, savedMentions, message)
        }
        
        currentTextView = null
        currentMessage = null
        savedRawText = ""
        savedMentions = null
        actionListener?.onDismiss()
    }
}
