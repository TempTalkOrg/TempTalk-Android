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






internal fun Rect.toRootRelative(root: Rect): PopupRect =
    PopupRect(left - root.left, top - root.top, right - root.left, bottom - root.top)


internal fun Rect.toPopupRect(): PopupRect = PopupRect(left, top, right, bottom)







class MessageActionPopup(
    private val activity: FragmentActivity
) {
    
    


    enum class MenuMode {
        FULL,      
        SELECTION  
    }
    
    


    interface Callbacks {
        




        fun onReactionSelected(emoji: String, isRemove: Boolean)
        fun onMoreEmojiClick()
        fun onActionSelected(action: MessageAction.Type)
        fun onDismiss()
    }
    
    


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
    
    
    private var menuModeState = mutableStateOf(MenuMode.FULL)

    
    private val selectionRowsState = mutableStateOf(SelectionRowRects.NONE)

    
    private val bubbleBoundsState = mutableStateOf(PopupRect(0, 0, 0, 0))
    private val contentBoundsState = mutableStateOf(PopupRect(0, 0, 0, 0))

    
    
    
    
    private val showRequestState = mutableIntStateOf(0)
    private var showPending = false

    
    var onGestureEnd: (() -> Unit)? = null
    
    








    fun show(
        anchorView: View,
        config: MessageActionConfigBuilder.Config,
        containerView: View? = null,
        callbacks: Callbacks
    ) {
        
        dismissInternal(notifyCallback = false)
        
        this.currentConfig = config
        this.callbacks = callbacks
        this._isShowing = true
        
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val rootBounds = Rect().also { rootView.getGlobalVisibleRect(it) }
        
        val contentBounds = Rect().also { ChatContentBand.onScreen(containerView ?: rootView, it) }.toRootRelative(rootBounds)
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

        
        
        
        cancelOngoingTouch(anchorView)
    }

    private fun createComposeView(
        config: MessageActionConfigBuilder.Config,
        callbacks: Callbacks,
    ): ComposeView {
        
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
        
        
        val selectionActions = listOf(
            MessageAction.copy(),
            MessageAction.forward(),
            MessageAction.selectAll()
        )
        
        
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

    



    fun updateSelectionRows(rows: SelectionRowRects) {
        if (_isShowing) selectionRowsState.value = rows
    }

    





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

    



    fun updateBounds(bubble: Rect, content: Rect) {
        if (!_isShowing) return
        val rootBounds = Rect().also { activity.findViewById<ViewGroup>(android.R.id.content).getGlobalVisibleRect(it) }
        bubbleBoundsState.value = bubble.toRootRelative(rootBounds)
        contentBoundsState.value = content.toRootRelative(rootBounds)
    }

    
    fun hideAll() {
        showPending = false
        if (_isShowing) overlayContainer?.hideAllChildren()
    }

    
    val isGestureActive: Boolean
        get() = overlayContainer?.isGestureActive == true

    
    internal val bubbleBounds: PopupRect
        get() = bubbleBoundsState.value

    
    internal val selectionRows: SelectionRowRects
        get() = selectionRowsState.value
    
    


    private fun cancelOngoingTouch(view: View) {
        val now = SystemClock.uptimeMillis()
        val cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            
            view.dispatchTouchEvent(cancelEvent)
            
            
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
    
    


    fun dismiss() {
        dismissInternal(notifyCallback = true)
    }
    
    



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
    
    


    val isShowing: Boolean
        get() = _isShowing
    
    



    fun getOverlayContainer(): ViewGroup? = overlayContainer
    
    


    val currentMessage: TextChatMessage?
        get() = currentConfig?.message
    
    


    fun setSelectionMenuCallbacks(callbacks: SelectionMenuCallbacks) {
        this.selectionMenuCallbacks = callbacks
    }
    
    


    fun showSelectionMenu() {
        if (_isShowing) {
            menuModeState.value = MenuMode.SELECTION
        }
    }
    
    


    fun showFullMenu() {
        if (_isShowing) {
            menuModeState.value = MenuMode.FULL
        }
    }
    
    


    val menuMode: MenuMode
        get() = menuModeState.value
    
    private fun cleanup() {
        currentConfig = null
        callbacks = null
        onGestureEnd = null
    }
}
