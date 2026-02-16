package com.difft.android.chat.ui.textpreview

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.chat.ui.messageaction.ArrowConfig
import com.difft.android.chat.ui.messageaction.MessageAction
import com.difft.android.chat.ui.messageaction.MessageActionContent

/**
 * Selection popup for TextPreviewActivity.
 * Shows copy, forward, translate, and optionally select all actions.
 * Positions itself to avoid covering the selected text.
 */
class TextPreviewSelectionPopup(private val activity: Activity) {
    
    interface Callbacks {
        fun onCopy()
        fun onForward()
        fun onTranslate()
        fun onSelectAll()
        fun onDismiss()
    }
    
    private var overlayContainer: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var callbacks: Callbacks? = null
    private var _isShowing = false
    private var popupBounds: Rect? = null
    
    val isShowing: Boolean
        get() = _isShowing
    
    /** Get popup bounds for hit testing */
    fun getPopupBounds(): Rect? = popupBounds
    
    /**
     * Show selection popup.
     * 
     * @param selectionBounds The bounds of the selected text in screen coordinates
     * @param isFullSelection Whether all text is selected (hides SelectAll option)
     * @param callbacks Interaction callbacks
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(
        selectionBounds: Rect,
        isFullSelection: Boolean,
        callbacks: Callbacks
    ) {
        if (_isShowing) {
            dismiss()
        }
        
        this.callbacks = callbacks
        this._isShowing = true
        
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val rootBounds = Rect()
        rootView.getGlobalVisibleRect(rootBounds)
        
        // Convert selection bounds to be relative to rootView
        val relativeSelectionBounds = Rect(
            selectionBounds.left - rootBounds.left,
            selectionBounds.top - rootBounds.top,
            selectionBounds.right - rootBounds.left,
            selectionBounds.bottom - rootBounds.top
        )
        
        val screenWidth = WindowSizeClassUtil.getWindowWidthPx(activity)
        val screenHeight = WindowSizeClassUtil.getWindowHeightPx(activity)
        
        // Build actions
        val actions = buildList {
            add(MessageAction.copy())
            add(MessageAction.forward())
            add(MessageAction.translateAction())
            if (!isFullSelection) {
                add(MessageAction.selectAll())
            }
        }
        
        // Create ComposeView
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            
            setContent {
                DifftTheme {
                    TextPreviewSelectionOverlay(
                        selectionBounds = relativeSelectionBounds,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        actions = actions,
                        onActionClick = { action ->
                            when (action.type) {
                                MessageAction.Type.COPY -> {
                                    callbacks.onCopy()
                                    dismiss()
                                }
                                MessageAction.Type.FORWARD -> {
                                    callbacks.onForward()
                                    dismiss()
                                }
                                MessageAction.Type.TRANSLATE -> {
                                    callbacks.onTranslate()
                                    dismiss()
                                }
                                MessageAction.Type.SELECT_ALL -> {
                                    callbacks.onSelectAll()
                                    // Don't dismiss - will be repositioned after selection changes
                                }
                                else -> {}
                            }
                        },
                        onPopupPositioned = { bounds ->
                            popupBounds = bounds
                        }
                    )
                }
            }
        }
        
        // Create overlay container
        overlayContainer = object : FrameLayout(activity) {
            private var waitingForNewTouchSequence = true
            
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                val x = ev.x.toInt()
                val y = ev.y.toInt()
                
                // Wait for first ACTION_DOWN to start accepting events
                if (waitingForNewTouchSequence) {
                    if (ev.action == MotionEvent.ACTION_DOWN) {
                        waitingForNewTouchSequence = false
                    } else {
                        return true
                    }
                }
                
                // Check if touch is inside popup bounds
                val pBounds = popupBounds
                if (pBounds != null && pBounds.contains(x, y)) {
                    return super.dispatchTouchEvent(ev)
                }
                
                // Touch outside popup - dismiss on ACTION_UP
                if (ev.action == MotionEvent.ACTION_UP) {
                    dismiss()
                }
                
                // Let touch pass through to underlying views (for handle dragging)
                return false
            }
        }.apply {
            addView(composeView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        
        rootView.addView(overlayContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    
    /**
     * Update popup position when selection changes.
     */
    fun updatePosition(selectionBounds: Rect, isFullSelection: Boolean) {
        if (!_isShowing) return
        
        // Dismiss and re-show with new position
        val currentCallbacks = callbacks ?: return
        dismiss()
        show(selectionBounds, isFullSelection, currentCallbacks)
    }
    
    fun dismiss() {
        if (!_isShowing) return
        
        _isShowing = false
        
        composeView?.disposeComposition()
        composeView = null
        
        overlayContainer?.let { container ->
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
            rootView?.removeView(container)
        }
        overlayContainer = null
        popupBounds = null
        
        callbacks?.onDismiss()
        callbacks = null
    }
}

@Composable
private fun TextPreviewSelectionOverlay(
    selectionBounds: Rect,
    screenWidth: Int,
    screenHeight: Int,
    actions: List<MessageAction>,
    onActionClick: (MessageAction) -> Unit,
    onPopupPositioned: (Rect) -> Unit
) {
    val density = LocalDensity.current
    
    var popupWidth by remember { mutableIntStateOf(0) }
    var popupHeight by remember { mutableIntStateOf(0) }
    var isMeasured by remember { mutableStateOf(false) }
    
    val edgePaddingPx = with(density) { 16.dp.toPx().toInt() }
    val gapPx = with(density) { 8.dp.toPx().toInt() }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Measure phase
        if (!isMeasured) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(-9999, -9999) }
                    .onGloballyPositioned { coordinates ->
                        if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                            popupWidth = coordinates.size.width
                            popupHeight = coordinates.size.height
                            isMeasured = true
                        }
                    }
            ) {
                MessageActionContent(
                    reactions = emptyList(),
                    selectedEmojis = emptySet(),
                    showReactionBar = false,
                    quickActions = actions,
                    onReactionClick = { _, _ -> },
                    onMoreEmojiClick = { },
                    onActionClick = onActionClick
                )
            }
        }
        
        // Position phase
        if (isMeasured && popupWidth > 0 && popupHeight > 0) {
            // Calculate X position - centered horizontally
            val x = ((screenWidth - popupWidth) / 2).coerceIn(edgePaddingPx, screenWidth - popupWidth - edgePaddingPx)
            
            // Calculate selection height
            val selectionHeight = selectionBounds.bottom - selectionBounds.top
            val selectionCenterY = (selectionBounds.top + selectionBounds.bottom) / 2
            
            // Position strategy:
            // 1. If selection is very tall (> 50% screen), center popup vertically
            // 2. Otherwise, try above selection, then below, then wherever fits
            val y: Int
            val showArrow: Boolean
            val arrowPointsUp: Boolean
            
            if (selectionHeight > screenHeight * 0.5) {
                // Large selection - center popup on screen
                y = (screenHeight - popupHeight) / 2
                showArrow = false
                arrowPointsUp = false
            } else {
                val spaceAbove = selectionBounds.top - edgePaddingPx
                val spaceBelow = screenHeight - selectionBounds.bottom - edgePaddingPx
                
                val idealYAbove = selectionBounds.top - gapPx - popupHeight
                val idealYBelow = selectionBounds.bottom + gapPx
                
                val canFitAbove = spaceAbove >= popupHeight + gapPx
                val canFitBelow = spaceBelow >= popupHeight + gapPx
                
                when {
                    canFitAbove -> {
                        // Prefer above
                        y = idealYAbove.coerceAtLeast(edgePaddingPx)
                        showArrow = true
                        arrowPointsUp = false  // Arrow points down (popup above selection)
                    }
                    canFitBelow -> {
                        // Fall back to below
                        y = idealYBelow.coerceAtMost(screenHeight - popupHeight - edgePaddingPx)
                        showArrow = true
                        arrowPointsUp = true  // Arrow points up (popup below selection)
                    }
                    else -> {
                        // Neither fits well - use the side with more space
                        if (spaceAbove >= spaceBelow) {
                            y = edgePaddingPx
                            showArrow = false
                            arrowPointsUp = false
                        } else {
                            y = screenHeight - popupHeight - edgePaddingPx
                            showArrow = false
                            arrowPointsUp = true
                        }
                    }
                }
            }
            
            // Report bounds for touch handling
            onPopupPositioned(Rect(x, y, x + popupWidth, y + popupHeight))
            
            // Calculate arrow offset (point to selection center)
            val arrowConfig = if (showArrow) {
                val selectionCenterX = (selectionBounds.left + selectionBounds.right) / 2
                val minArrowOffsetPx = with(density) { 16.dp.toPx() }
                val maxArrowOffsetPx = (popupWidth - minArrowOffsetPx).coerceAtLeast(minArrowOffsetPx)
                val arrowOffsetXPx = (selectionCenterX - x).toFloat().coerceIn(minArrowOffsetPx, maxArrowOffsetPx)
                ArrowConfig(
                    isBelow = arrowPointsUp,
                    arrowOffsetX = with(density) { arrowOffsetXPx.toDp() }
                )
            } else {
                null
            }
            
            Box(
                modifier = Modifier.offset { IntOffset(x, y) }
            ) {
                MessageActionContent(
                    reactions = emptyList(),
                    selectedEmojis = emptySet(),
                    showReactionBar = false,
                    quickActions = actions,
                    onReactionClick = { _, _ -> },
                    onMoreEmojiClick = { },
                    onActionClick = onActionClick,
                    arrowConfig = arrowConfig
                )
            }
        }
    }
}
