package com.difft.android.chat.ui.messageaction

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.base.utils.dp as dpToPx

/**
 * Message action popup that displays emoji reactions and quick actions
 * 
 * Note: Uses full-screen overlay with Box+offset instead of Compose Popup
 * to avoid affecting the window background drawable
 */
class MessageActionPopup(
    private val activity: FragmentActivity
) {
    
    /**
     * Menu mode - full menu or selection menu
     */
    enum class MenuMode {
        FULL,      // Full menu with reactions and all actions
        SELECTION  // Selection menu with Copy, Forward, SelectAll only
    }
    
    /**
     * Callbacks for popup interactions
     */
    interface Callbacks {
        /**
         * Called when emoji reaction is selected
         * @param emoji The emoji that was clicked
         * @param isRemove true if user wants to remove existing reaction, false to add new
         */
        fun onReactionSelected(emoji: String, isRemove: Boolean)
        fun onMoreEmojiClick()
        fun onActionSelected(action: MessageAction.Type)
        fun onDismiss()
    }
    
    /**
     * Callbacks for selection menu actions
     */
    interface SelectionMenuCallbacks {
        fun onCopy()
        fun onForward()
        fun onSelectAll()
    }
    
    private var overlayContainer: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var moreActionsDialog: ComposeDialog? = null
    private var currentConfig: MessageActionConfigBuilder.Config? = null
    private var callbacks: Callbacks? = null
    private var selectionMenuCallbacks: SelectionMenuCallbacks? = null
    private var _isShowing = false
    
    // Menu mode state - mutable state for Compose
    private var menuModeState = mutableStateOf(MenuMode.FULL)
    
    // Touch passthrough support
    private var textViewBounds: Rect? = null
    private var popupBounds: Rect? = null
    private var currentTextView: View? = null
    
    /**
     * Show the message action popup
     * 
     * @param anchorView The view to anchor the popup to
     * @param config Configuration built by MessageActionConfigBuilder
     * @param containerView Optional container view to determine available bounds (e.g., RecyclerView)
     * @param restoreWindowBackground Whether to restore window background after showing popup (default true).
     *        Set to false when background is set on a view rather than window (e.g., in popup-style activities)
     * @param textView Optional text view that should remain interactive for text selection
     * @param callbacks Callbacks for user interactions
     */
    fun show(
        anchorView: View,
        config: MessageActionConfigBuilder.Config,
        containerView: View? = null,
        restoreWindowBackground: Boolean = true,
        textView: View? = null,
        callbacks: Callbacks
    ) {
        // Dismiss any existing popup
        dismissInternal(notifyCallback = false)
        
        this.currentConfig = config
        this.callbacks = callbacks
        this._isShowing = true
        this.currentTextView = textView
        
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val anchorBounds = config.anchorBounds
        
        // Get rootView bounds to calculate relative positions
        val rootBounds = Rect()
        rootView.getGlobalVisibleRect(rootBounds)
        
        // Save textView bounds (relative to rootView) for touch passthrough
        textView?.let { tv ->
            val tvBounds = Rect()
            tv.getGlobalVisibleRect(tvBounds)
            textViewBounds = Rect(
                tvBounds.left - rootBounds.left,
                tvBounds.top - rootBounds.top,
                tvBounds.right - rootBounds.left,
                tvBounds.bottom - rootBounds.top
            )
        } ?: run {
            textViewBounds = null
        }
        
        // Get the actual visible content area from container or fallback to rootView
        val contentBounds = Rect()
        (containerView ?: rootView).getGlobalVisibleRect(contentBounds)
        
        // Convert anchor bounds to be relative to rootView
        val relativeAnchorBounds = Rect(
            anchorBounds.left - rootBounds.left,
            anchorBounds.top - rootBounds.top,
            anchorBounds.right - rootBounds.left,
            anchorBounds.bottom - rootBounds.top
        )
        
        // Convert content bounds to be relative to rootView
        val relativeContentBounds = Rect(
            contentBounds.left - rootBounds.left,
            contentBounds.top - rootBounds.top,
            contentBounds.right - rootBounds.left,
            contentBounds.bottom - rootBounds.top
        )
        
        val screenWidth = WindowSizeClassUtil.getWindowWidthPx(activity)
        val isOutgoing = config.isOutgoing
        
        // Capture callbacks for use in lambdas
        val onDismissCallback = { notifyDismiss(callbacks) }
        val onReactionCallback = { emoji: String, isRemove: Boolean -> 
            callbacks.onReactionSelected(emoji, isRemove)
            notifyDismiss(callbacks)
        }
        val onMoreEmojiCallback = { 
            callbacks.onMoreEmojiClick()
            notifyDismiss(callbacks)
        }
        val onActionCallback = { action: MessageAction ->
            when (action.type) {
                MessageAction.Type.MORE -> showMoreActionsSheet(config.allActions, callbacks)
                else -> {
                    callbacks.onActionSelected(action.type)
                    notifyDismiss(callbacks)
                }
            }
        }
        
        // Callback to update popup bounds when measured
        val onPopupMeasured = { bounds: Rect ->
            popupBounds = bounds
        }
        
        // Reset menu mode to full
        menuModeState.value = MenuMode.FULL
        
        // Selection menu actions
        val selectionActions = listOf(
            MessageAction.copy(),
            MessageAction.forward(),
            MessageAction.selectAll()
        )
        
        // Selection menu action handler
        val onSelectionActionClick: (MessageAction) -> Unit = { action ->
            when (action.type) {
                MessageAction.Type.COPY -> selectionMenuCallbacks?.onCopy()
                MessageAction.Type.FORWARD -> selectionMenuCallbacks?.onForward()
                MessageAction.Type.SELECT_ALL -> selectionMenuCallbacks?.onSelectAll()
                else -> Unit
            }
        }
        
        // Create ComposeView with full-screen overlay
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            
            setContent {
                DifftTheme {
                    var showPopup by remember { mutableStateOf(true) }
                    val menuMode by menuModeState
                    
                    // Handle back press
                    BackHandler(enabled = showPopup) {
                        showPopup = false
                        onDismissCallback()
                    }
                    
                    if (showPopup) {
                        MessageActionPopupOverlay(
                            anchorBounds = relativeAnchorBounds,
                            contentBounds = relativeContentBounds,
                            screenWidth = screenWidth,
                            isOutgoing = isOutgoing,
                            config = config,
                            menuMode = menuMode,
                            selectionActions = selectionActions,
                            onPopupMeasured = onPopupMeasured,
                            onReactionClick = { emoji, isRemove ->
                                showPopup = false
                                onReactionCallback(emoji, isRemove)
                            },
                            onMoreEmojiClick = {
                                showPopup = false
                                onMoreEmojiCallback()
                            },
                            onActionClick = { action ->
                                showPopup = false
                                onActionCallback(action)
                            },
                            onSelectionActionClick = onSelectionActionClick
                        )
                    }
                }
            }
        }
        
        // Create overlay container that passes through touch events in textView area
        @SuppressLint("ClickableViewAccessibility")
        overlayContainer = object : FrameLayout(activity) {
            // Flag to ignore the first touch sequence (which is the continuation of the long-press that triggered the popup)
            private var waitingForNewTouchSequence = true
            
            // Track if a child view (like selection handles) is handling the touch
            private var childHandlingTouch = false
            
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                val x = ev.x.toInt()
                val y = ev.y.toInt()
                
                // Wait for the first ACTION_DOWN to start accepting touch events
                if (waitingForNewTouchSequence) {
                    if (ev.action == MotionEvent.ACTION_DOWN) {
                        waitingForNewTouchSequence = false
                    } else {
                        return true
                    }
                }
                
                // On ACTION_DOWN, check if any child view wants to handle the touch
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    childHandlingTouch = false
                    
                    // Let super try to dispatch to children first (handles, etc.)
                    // If a child handles it, super returns true and that child gets subsequent events
                    val handled = super.dispatchTouchEvent(ev)
                    if (handled) {
                        // Check if it was handled by a child (not just the ComposeView background)
                        // Selection handles will return true for ACTION_DOWN
                        childHandlingTouch = true
                        return true
                    }
                }
                
                // If a child is handling this touch sequence, continue dispatching to it
                if (childHandlingTouch) {
                    val result = super.dispatchTouchEvent(ev)
                    if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                        childHandlingTouch = false
                    }
                    return result
                }
                
                // Check if touch is in textView/content area
                // Don't forward to textView - this would trigger long click callbacks
                // Handle dragging is managed by overlay's child views directly
                val tvBounds = textViewBounds
                val inTextView = tvBounds != null && tvBounds.contains(x, y)
                
                // Consume touches in textView area and dismiss on tap
                if (inTextView) {
                    if (ev.action == MotionEvent.ACTION_UP) {
                        // Touch ended in textView area - dismiss the popup
                        dismiss()
                    }
                    return true
                }
                
                // Check if touch is outside popup bounds - if so, dismiss on ACTION_UP
                val pBounds = popupBounds
                if (pBounds != null && !pBounds.contains(x, y)) {
                    if (ev.action == MotionEvent.ACTION_UP) {
                        dismiss()
                    }
                    return true
                }
                
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            addView(composeView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        
        // Save original window background before adding ComposeView
        val originalBackground = if (restoreWindowBackground) {
            activity.window.decorView.background
        } else null
        
        // Full-screen overlay
        rootView.addView(overlayContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        // Immediately restore window background after adding ComposeView (synchronous, no delay)
        if (originalBackground != null) {
            activity.window.setBackgroundDrawable(originalBackground)
        }
        
        // Cancel any ongoing touch sequence on the anchor view to prevent swipe gestures
        // This is needed because the long-press that triggered the popup may still have
        // an active touch sequence that could trigger swipe-to-reply or other gestures
        cancelOngoingTouch(anchorView)
    }
    
    /**
     * Send ACTION_CANCEL to the view and its parent chain to cancel any ongoing touch/gesture
     */
    private fun cancelOngoingTouch(view: View) {
        val now = SystemClock.uptimeMillis()
        val cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            // Dispatch cancel to the view
            view.dispatchTouchEvent(cancelEvent)
            
            // Also send to parent views in case they're tracking gestures (like RecyclerView swipe)
            var parent = view.parent
            while (parent is View) {
                (parent as View).dispatchTouchEvent(cancelEvent)
                parent = parent.parent
            }
        } finally {
            cancelEvent.recycle()
        }
    }
    
    private fun notifyDismiss(callbacks: Callbacks) {
        _isShowing = false
        removeOverlay()
        callbacks.onDismiss()
        cleanup()
    }
    
    private fun removeOverlay() {
        composeView?.disposeComposition()
        composeView = null
        
        overlayContainer?.let { container ->
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
            rootView?.removeView(container)
        }
        overlayContainer = null
        
        textViewBounds = null
        popupBounds = null
        currentTextView = null
    }
    
    /**
     * Dismiss the popup and any open dialogs
     */
    fun dismiss() {
        dismissInternal(notifyCallback = true)
    }
    
    /**
     * Dismiss the popup without triggering onDismiss callback.
     * Used internally when switching between popups (e.g., from message action to text selection).
     */
    fun dismissSilently() {
        dismissInternal(notifyCallback = false)
    }
    
    private fun dismissInternal(notifyCallback: Boolean) {
        moreActionsDialog?.dismiss()
        moreActionsDialog = null
        
        val wasShowing = _isShowing
        _isShowing = false
        
        removeOverlay()
        
        if (wasShowing && notifyCallback) {
            callbacks?.onDismiss()
        }
        cleanup()
    }
    
    /**
     * Check if popup is currently showing
     */
    val isShowing: Boolean
        get() = _isShowing
    
    /**
     * Get the overlay container for adding additional views (e.g., selection handles).
     * Returns null if popup is not showing.
     */
    fun getOverlayContainer(): ViewGroup? = overlayContainer
    
    /**
     * Get current message if popup is showing
     */
    val currentMessage: TextChatMessage?
        get() = currentConfig?.message
    
    /**
     * Set callbacks for selection menu actions
     */
    fun setSelectionMenuCallbacks(callbacks: SelectionMenuCallbacks) {
        this.selectionMenuCallbacks = callbacks
    }
    
    /**
     * Switch to selection menu mode (Copy, Forward, SelectAll)
     */
    fun showSelectionMenu() {
        if (_isShowing) {
            menuModeState.value = MenuMode.SELECTION
        }
    }
    
    /**
     * Switch to full menu mode (reactions + all actions)
     */
    fun showFullMenu() {
        if (_isShowing) {
            menuModeState.value = MenuMode.FULL
        }
    }
    
    /**
     * Get current menu mode
     */
    val menuMode: MenuMode
        get() = menuModeState.value
    
    private fun showMoreActionsSheet(moreActions: List<MessageAction>, callbacks: Callbacks) {
        if (moreActions.isEmpty()) return
        
        // Hide popup without triggering onDismiss callback
        _isShowing = false
        removeOverlay()
        
        // Show bottom sheet
        moreActionsDialog = MoreActionsSheet.show(
            activity = activity,
            actions = moreActions,
            onActionClick = { action ->
                moreActionsDialog?.dismiss()
                moreActionsDialog = null
                callbacks.onActionSelected(action.type)
                callbacks.onDismiss()
                cleanup()
            },
            onDismiss = {
                moreActionsDialog = null
                callbacks.onDismiss()
                cleanup()
            }
        )
    }
    
    private fun cleanup() {
        currentConfig = null
        callbacks = null
    }
}

/**
 * Popup content positioned using Box + offset
 * Touch events are handled at the View level (see overlayContainer in MessageActionPopup)
 * This composable does NOT use pointerInput to allow touch passthrough
 */
@Composable
private fun MessageActionPopupOverlay(
    anchorBounds: Rect,
    contentBounds: Rect,
    screenWidth: Int,
    isOutgoing: Boolean,
    config: MessageActionConfigBuilder.Config,
    menuMode: MessageActionPopup.MenuMode,
    selectionActions: List<MessageAction>,
    onPopupMeasured: (Rect) -> Unit,
    onReactionClick: (emoji: String, isRemove: Boolean) -> Unit,
    onMoreEmojiClick: () -> Unit,
    onActionClick: (MessageAction) -> Unit,
    onSelectionActionClick: (MessageAction) -> Unit
) {
    val density = LocalDensity.current
    // Re-measure width when menu mode changes to keep popup close to bubble
    // Height measurement doesn't need to reset - Y position stays stable
    var popupWidth by remember(menuMode) { mutableIntStateOf(0) }
    var popupHeight by remember { mutableIntStateOf(0) }
    val isMeasured = popupWidth > 0 && popupHeight > 0
    
    // Constants (pixels)
    val edgePaddingPx = 8.dpToPx
    val arrowGapPx = 2.dpToPx
    
    // Use actual content area bounds
    val minY = contentBounds.top + edgePaddingPx
    val maxY = contentBounds.bottom - edgePaddingPx
    
    // Determine content based on menu mode
    val isFullMode = menuMode == MessageActionPopup.MenuMode.FULL
    val reactions = if (isFullMode) config.reactions else emptyList()
    val selectedEmojis = if (isFullMode) config.selectedEmojis else emptySet()
    val showReactionBar = isFullMode && config.showReactionBar
    val quickActions = if (isFullMode) config.quickActions else selectionActions
    val actionClickHandler: (MessageAction) -> Unit = if (isFullMode) onActionClick else onSelectionActionClick
    
    // Container for positioned content - NO pointerInput here, touch is handled at View level
    Box(modifier = Modifier.fillMaxSize()) {
        // Measure phase: invisible content to get dimensions
        if (!isMeasured) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(-9999, -9999) }
                    .onGloballyPositioned { coordinates ->
                        if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                            popupWidth = coordinates.size.width
                            popupHeight = coordinates.size.height
                        }
                    }
            ) {
                MessageActionContent(
                    reactions = reactions,
                    selectedEmojis = selectedEmojis,
                    showReactionBar = showReactionBar,
                    quickActions = quickActions,
                    arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = with(density) { 100.toDp() }),
                    onReactionClick = { _, _ -> },
                    onMoreEmojiClick = { },
                    onActionClick = { }
                )
            }
        }
        
        // Display phase: positioned content
        if (isMeasured) {
            val x = calculatePopupX(screenWidth, popupWidth, edgePaddingPx, isOutgoing)
            val (y, isBelow) = calculatePopupY(
                anchorBounds, popupHeight, minY, maxY, arrowGapPx
            )
            
            // Report popup bounds for View-level touch handling
            onPopupMeasured(Rect(x, y, x + popupWidth, y + popupHeight))
            
            // Calculate arrow position
            val anchorCenterX = (anchorBounds.left + anchorBounds.right) / 2
            val minArrowOffsetPx = 16.dpToPx
            val maxArrowOffsetPx = (popupWidth - 16.dpToPx).coerceAtLeast(minArrowOffsetPx)
            val arrowOffsetXPx = (anchorCenterX - x).coerceIn(minArrowOffsetPx, maxArrowOffsetPx)
            val arrowOffsetX: Dp = with(density) { arrowOffsetXPx.toDp() }
            
            Box(
                modifier = Modifier.offset { IntOffset(x, y) }
            ) {
                MessageActionContent(
                    reactions = reactions,
                    selectedEmojis = selectedEmojis,
                    showReactionBar = showReactionBar,
                    quickActions = quickActions,
                    arrowConfig = ArrowConfig(isBelow = isBelow, arrowOffsetX = arrowOffsetX),
                    onReactionClick = onReactionClick,
                    onMoreEmojiClick = onMoreEmojiClick,
                    onActionClick = actionClickHandler
                )
            }
        }
    }
}

private fun calculatePopupX(screenWidth: Int, popupWidth: Int, edgePaddingPx: Int, isOutgoing: Boolean): Int {
    return if (isOutgoing) {
        screenWidth - popupWidth - edgePaddingPx
    } else {
        edgePaddingPx
    }
}

private fun calculatePopupY(
    anchorBounds: Rect,
    popupHeight: Int,
    minY: Int,
    maxY: Int,
    arrowGapPx: Int
): Pair<Int, Boolean> {
    val idealYBelow = anchorBounds.bottom + arrowGapPx
    val idealYAbove = anchorBounds.top - popupHeight - arrowGapPx
    
    val canFitBelow = (idealYBelow + popupHeight) <= maxY
    val canFitAbove = idealYAbove >= minY
    
    return when {
        canFitBelow -> idealYBelow to true
        canFitAbove -> idealYAbove to false
        else -> {
            val spaceBelow = maxY - anchorBounds.bottom
            val spaceAbove = anchorBounds.top - minY
            if (spaceBelow >= spaceAbove) {
                idealYBelow.coerceAtMost(maxY - popupHeight) to true
            } else {
                idealYAbove.coerceAtLeast(minY) to false
            }
        }
    }
}
