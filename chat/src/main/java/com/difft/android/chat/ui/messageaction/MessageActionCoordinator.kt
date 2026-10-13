package com.difft.android.chat.ui.messageaction

import android.graphics.Point
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.common.TextTruncationUtil
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isLongTextAttachment
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
 * 2. Every settled selection change (initial select-all, Select All button, handle drag end) fires the
 *    SelectionCallback: a partial selection switches the menu to SELECTION mode, a full or empty one
 *    back to the full menu
 * 3. A handle drag past touch slop hides the menu; drag end shows it again, re-anchored to the
 *    selection rows ([SelectionMenuSync])
 * 4. A list scroll keeps the selection: the menu hides while the list moves and comes back
 *    re-anchored when it settles; the message leaving the viewport or its text changing on rebind
 *    dismisses ([ListScrollSync])
 */
class MessageActionCoordinator(
    private val activity: FragmentActivity,
    private val globalConfigsManager: IGlobalConfigsManager,
    /** False on a build that ships no translate backend. Forwarded to the action builder. */
    private val translateSupported: Boolean,
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
        fun onFavoriteGif(message: TextChatMessage)
        fun onDismiss()
    }
    
    private val configBuilder = MessageActionConfigBuilder(globalConfigsManager, translateSupported)
    private var messageActionPopup: MessageActionPopup? = null
    private var textSelectionManager: TextSelectionManager? = null
    private val selectionSync = SelectionMenuSync({ messageActionPopup }, { textSelectionManager })
    private val listSync = ListScrollSync({ messageActionPopup }, { textSelectionManager }, selectionSync, ::dismiss)
    
    private var currentMessage: TextChatMessage? = null
    private var currentTextView: TextView? = null
    private var currentAnchorView: View? = null
    private var currentContainerView: View? = null
    private var currentConfig: MessageActionConfigBuilder.Config? = null
    private var actionListener: ActionListener? = null
    private var lastSelStart = -1
    private var lastSelEnd = -1
    private var isInitializing = true  // Skip selection changes during initialization
    private var textSelectionEnabled = true  // Can be disabled for confidential messages
    // Save original text for restoring OnTouchListener (message.message may change due to RecyclerView recycling)
    private var savedRawText: String = ""
    private var savedMentions: List<Mention>? = null
    
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
                    // Pass null (not "") when nothing is actually selected so the listener's
                    // null-check correctly distinguishes "full-message copy" vs "partial
                    // copy". Selection menu mode normally always has a selection, but
                    // textSelectionManager can be in a transient detached state; treat that
                    // as null instead of silently copying an empty string and toasting "Copied".
                    val selectedText = resolveSelectionForFullMessage(currentMessage)
                    currentMessage?.let { msg ->
                        actionListener?.onCopy(msg, selectedText)
                    }
                    dismiss()
                }

                override fun onForward() {
                    // Same null-vs-empty discipline as onCopy above.
                    val selectedText = resolveSelectionForFullMessage(currentMessage)
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
                callbacks = createPopupCallbacks()
            )
            popup.onGestureEnd = listSync::onGestureEnd
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
        
        // Follow list scrolls; dismiss when the message leaves the viewport or its text changes on rebind
        (findRecyclerView(messageView) ?: findRecyclerView(containerView))?.let {
            listSync.attach(it, messageView, containerView, textView)
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
     * Dismiss all popups and clear state
     */
    fun dismiss() {
        // Remove the list listeners first (the anchor's detach listener must not re-enter)
        listSync.detach()
        
        messageActionPopup?.dismiss()
        messageActionPopup = null
        
        // Clean up custom text selection
        textSelectionManager?.detach()
        textSelectionManager = null
        selectionSync.reset()
        
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

    @VisibleForTesting internal val popupForTest: MessageActionPopup? get() = messageActionPopup
    @VisibleForTesting internal val selectionManagerForTest: TextSelectionManager? get() = textSelectionManager

    /**
     * Resolve the selected text to pass to copy/forward.
     *
     * A long-text bubble only renders a truncated preview (capped at maxLines) followed by a
     * "Read more" span; the complete body lives in the attachment file, not in the TextView.
     * When the user's selection runs to the end of that preview (i.e. it includes "Read more"),
     * the intent is "the whole message", but getSelectedText() can only return the truncated
     * preview. Returning null here routes such selections to the full-message path
     * (copyMessageContent / forwardMessage), which reads the full text from the attachment.
     * Genuine partial selections (that stop before the end) still forward/copy just the snippet.
     */
    private fun resolveSelectionForFullMessage(message: TextChatMessage?): String? {
        val selectedText = textSelectionManager?.getSelectedText()?.takeIf { it.isNotEmpty() }
        if (selectedText == null) return null
        if (message?.isLongTextAttachment() == true && selectionReachesTextEnd()) {
            return null
        }
        return selectedText
    }

    /**
     * True when the current selection extends to the end of the bubble text — for long-text
     * bubbles the trailing "Read more" span always sits at the very end, so a selection whose
     * end reaches the text length necessarily includes it.
     */
    private fun selectionReachesTextEnd(): Boolean {
        val manager = textSelectionManager ?: return false
        val length = currentTextView?.text?.length ?: return false
        return manager.hasSelection() && manager.getSelectionEnd() >= length
    }

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
                currentMessage?.let { msg -> actionListener?.let { action.dispatchTo(it, msg) } }
            }
            
            override fun onDismiss() {
                cleanupAfterDismiss()
            }
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
            
            // Assigned before selectAll(): the first callback re-anchors through this field (#1294)
            TextSelectionManager(textView).also { textSelectionManager = it }.apply {
                setEnabled(textSelectionEnabled)
                onSelectionChanged = object : TextSelectionManager.SelectionCallback {
                    override fun onSelectionChanged(start: Int, end: Int, isFullSelect: Boolean) {
                        // Every settled change, not only drag end ("Select All" has no drag)
                        selectionSync.reanchor()
                        handleCustomSelectionChanged(start, end, isFullSelect)
                    }
                }
                handleDragCallback = selectionSync.dragCallback
                // Pass containerView (e.g., RecyclerView) to clip highlight bounds
                attachToOverlay(overlayContainer, currentContainerView)
                
                // Select all text initially
                selectAll()
            }
        }
    }
    
    /**
     * Handle selection changes from TextSelectionManager.
     * Fires on every settled selection change (initial select-all, Select All button, handle drag end):
     * a partial selection switches the menu to SELECTION mode, a full or empty one to the full menu.
     * Ignored while [isInitializing], so the initial select-all leaves the full menu in place.
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
        listSync.detach()
        // Clean up custom text selection
        textSelectionManager?.detach()
        textSelectionManager = null
        selectionSync.reset()
        
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
