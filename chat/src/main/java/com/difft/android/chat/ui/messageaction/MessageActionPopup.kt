package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.message.TextChatMessage

/**
 * The only window-to-root conversion for the message action menu (#1294): [this] comes from
 * `getGlobalVisibleRect` (window space) and [root] is `android.R.id.content`'s, so the window
 * offset cancels (split-screen / freeform safe).
 */
internal fun Rect.toRootRelative(root: Rect): PopupRect =
    PopupRect(left - root.left, top - root.top, right - root.left, bottom - root.top)

/** Selection rows are already overlay-local (== root-relative), so this is an identity copy. */
internal fun Rect.toPopupRect(): PopupRect = PopupRect(left, top, right, bottom)

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
    
    private var overlayContainer: MessageActionOverlayLayout? = null
    private var composeView: ComposeView? = null
    private var currentConfig: MessageActionConfigBuilder.Config? = null
    private var callbacks: Callbacks? = null
    private var selectionMenuCallbacks: SelectionMenuCallbacks? = null
    private var _isShowing = false
    
    // Menu mode state - mutable state for Compose
    private var menuModeState = mutableStateOf(MenuMode.FULL)

    // Visible selection-edge rows the menu anchors to; NONE means anchor to the bubble (#1294).
    private val selectionRowsState = mutableStateOf(SelectionRowRects.NONE)

    // Root-relative bubble and visible content area; refreshed when a list scroll settles (#1294).
    private val bubbleBoundsState = mutableStateOf(PopupRect(0, 0, 0, 0))
    private val contentBoundsState = mutableStateOf(PopupRect(0, 0, 0, 0))

    // A show request is applied from a SideEffect of the composition that carries the latest
    // bounds and rows (#1294). Flipping the View VISIBLE right away draws one frame at the stale
    // placement when the request comes from a frame callback (a fling settling), since the state
    // writes above only land on the next recomposition. The counter forces that recomposition.
    private val showRequestState = mutableIntStateOf(0)
    private var showPending = false

    /** Called after every UP or CANCEL that ends a gesture on the overlay. */
    var onGestureEnd: (() -> Unit)? = null
    
    /**
     * Show the message action popup
     * 
     * @param anchorView The view to anchor the popup to
     * @param config Configuration built by MessageActionConfigBuilder
     * @param containerView Optional container view to determine available bounds (e.g., RecyclerView).
     *        A RecyclerView is also scrolled by touches outside the menu and handles (#1294).
     * @param callbacks Callbacks for user interactions
     */
    fun show(
        anchorView: View,
        config: MessageActionConfigBuilder.Config,
        containerView: View? = null,
        callbacks: Callbacks
    ) {
        // Dismiss any existing popup
        dismissInternal(notifyCallback = false)
        
        this.currentConfig = config
        this.callbacks = callbacks
        this._isShowing = true
        
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val rootBounds = Rect().also { rootView.getGlobalVisibleRect(it) }
        // Visible content area from the container (e.g., RecyclerView), falling back to rootView
        val contentBounds = Rect().also { (containerView ?: rootView).getGlobalVisibleRect(it) }.toRootRelative(rootBounds)
        val bubbleBounds = config.anchorBounds.toRootRelative(rootBounds)

        menuModeState.value = MenuMode.FULL
        selectionRowsState.value = SelectionRowRects.NONE
        bubbleBoundsState.value = bubbleBounds
        contentBoundsState.value = contentBounds
        showPending = false
        L.i {
            "[MessageAction] show outgoing=${config.isOutgoing} " +
                "content=${contentBounds.width}x${contentBounds.height} bubbleH=${bubbleBounds.height}"
        }

        val menuView = createComposeView(config, callbacks)
        composeView = menuView
        overlayContainer = MessageActionOverlayLayout(activity, menuView, onDismissRequest = ::dismiss).also {
            it.recyclerView = containerView as? RecyclerView
            it.onGestureEnd = { onGestureEnd?.invoke() }
            rootView.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Cancel any ongoing touch sequence on the anchor view to prevent swipe gestures
        // This is needed because the long-press that triggered the popup may still have
        // an active touch sequence that could trigger swipe-to-reply or other gestures
        cancelOngoingTouch(anchorView)
    }

    private fun createComposeView(
        config: MessageActionConfigBuilder.Config,
        callbacks: Callbacks,
    ): ComposeView {
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
            callbacks.onActionSelected(action.type)
            notifyDismiss(callbacks)
        }
        
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
        
        return ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            
            setContent {
                DifftTheme(applyWindowBackground = false) {
                    var showPopup by remember { mutableStateOf(true) }
                    val menuMode by menuModeState
                    val selectionRows by selectionRowsState
                    val bubbleBounds by bubbleBoundsState
                    val contentBounds by contentBoundsState
                    val showRequest = showRequestState.intValue

                    // Handle back press
                    BackHandler(enabled = showPopup) {
                        showPopup = false
                        onDismissCallback()
                    }
                    
                    if (showPopup) {
                        MessageActionPopupOverlay(
                            bubbleBounds = bubbleBounds,
                            contentBounds = contentBounds,
                            selectionRows = selectionRows,
                            isOutgoing = config.isOutgoing,
                            config = config,
                            menuMode = menuMode,
                            selectionActions = selectionActions,
                            onMenuBoundsChanged = { overlayContainer?.menuBounds = it },
                            onPlaced = { placement, popupHeight ->
                                onMenuPlaced(placement, menuMode, popupHeight, contentBounds.height)
                            },
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
                        // Runs after each recomposition of this scope is applied, before the frame
                        // draws; onShowComposed acts only on the latest pending request.
                        SideEffect { onShowComposed(showRequest) }
                    }
                }
            }
        }
    }

    private fun onShowComposed(request: Int) {
        if (!showPending || request != showRequestState.intValue) return
        showPending = false
        overlayContainer?.setMenuVisible(true)
    }

    private fun onMenuPlaced(placement: PopupPlacement, menuMode: MenuMode, popupHeight: Int, contentHeight: Int) {
        L.i {
            "[MessageAction] placed anchor=${placement.anchor.mode} placement=${placement.placementMode} " +
                "below=${placement.isBelow} menu=$menuMode h=$popupHeight contentH=$contentHeight"
        }
    }

    /**
     * Re-anchor the menu to the visible selection-edge rows (overlay-local). [SelectionRowRects.NONE]
     * anchors to the bubble. No-op when not showing: late calls can arrive while the overlay is removed.
     */
    fun updateSelectionRows(rows: SelectionRowRects) {
        if (_isShowing) selectionRowsState.value = rows
    }

    /**
     * Hide the menu during a handle drag or list scroll (composition kept) and show it again
     * afterwards. Hiding is immediate; showing waits for the next composition so the menu is never
     * drawn at a placement older than the bounds and rows written before the request (a size change
     * in the same dispatch re-measures first, so that frame shows nothing). A hide cancels a pending show.
     */
    fun setMenuVisible(visible: Boolean) {
        val container = overlayContainer?.takeIf { _isShowing } ?: return
        if (!visible) {
            showPending = false
            container.setMenuVisible(false)
        } else if (!container.isMenuVisible && !showPending) {
            showPending = true
            showRequestState.intValue++
        }
    }

    /**
     * Refresh the bubble and visible content area from window rects (`getGlobalVisibleRect`) after
     * the list moved (#1294). No-op when not showing.
     */
    fun updateBounds(bubble: Rect, content: Rect) {
        if (!_isShowing) return
        val rootBounds = Rect().also { activity.findViewById<ViewGroup>(android.R.id.content).getGlobalVisibleRect(it) }
        bubbleBoundsState.value = bubble.toRootRelative(rootBounds)
        contentBoundsState.value = content.toRootRelative(rootBounds)
    }

    /** Hide the menu, highlight and handles at once, ahead of a deferred dismiss; cancels a pending show. */
    fun hideAll() {
        showPending = false
        if (_isShowing) overlayContainer?.hideAllChildren()
    }

    /** True while a gesture that started on the overlay is still down. */
    val isGestureActive: Boolean
        get() = overlayContainer?.isGestureActive == true

    /** Current bubble bounds (root-relative), for tests. */
    internal val bubbleBounds: PopupRect
        get() = bubbleBoundsState.value

    /** Current selection rows, for tests. */
    internal val selectionRows: SelectionRowRects
        get() = selectionRowsState.value
    
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
    
    private fun cleanup() {
        currentConfig = null
        callbacks = null
        onGestureEnd = null
    }
}
