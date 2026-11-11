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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.floatingMenuEntrance
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.chat.ui.messageaction.MessageAction
import com.difft.android.chat.ui.messageaction.MessageActionContent
import com.difft.android.chat.ui.messageaction.computeMaxPanelWidth






class TextPreviewSelectionPopup(
    private val activity: Activity,
    
    private val translateSupported: Boolean,
) {
    
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
    
    
    fun getPopupBounds(): Rect? = popupBounds
    
    






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
        
        
        val relativeSelectionBounds = Rect(
            selectionBounds.left - rootBounds.left,
            selectionBounds.top - rootBounds.top,
            selectionBounds.right - rootBounds.left,
            selectionBounds.bottom - rootBounds.top
        )
        
        val screenWidth = WindowSizeClassUtil.getWindowWidthPx(activity)
        val screenHeight = WindowSizeClassUtil.getWindowHeightPx(activity)
        
        
        val actions = buildList {
            add(MessageAction.copy())
            add(MessageAction.forward())
            if (translateSupported) add(MessageAction.translateAction())
            if (!isFullSelection) {
                add(MessageAction.selectAll())
            }
        }
        
        
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            
            setContent {
                DifftTheme(applyWindowBackground = false) {
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
        
        
        overlayContainer = object : FrameLayout(activity) {
            private var waitingForNewTouchSequence = true
            
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                val x = ev.x.toInt()
                val y = ev.y.toInt()
                
                
                if (waitingForNewTouchSequence) {
                    if (ev.action == MotionEvent.ACTION_DOWN) {
                        waitingForNewTouchSequence = false
                    } else {
                        return true
                    }
                }
                
                
                val pBounds = popupBounds
                if (pBounds != null && pBounds.contains(x, y)) {
                    return super.dispatchTouchEvent(ev)
                }
                
                
                if (ev.action == MotionEvent.ACTION_UP) {
                    dismiss()
                }
                
                
                return false
            }
        }.apply {
            addView(composeView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        
        rootView.addView(overlayContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    
    


    fun updatePosition(selectionBounds: Rect, isFullSelection: Boolean) {
        if (!_isShowing) return
        
        
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

    
    val maxPanelWidth = remember(screenWidth, edgePaddingPx) {
        computeMaxPanelWidth(screenWidth, edgePaddingPx, density)
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        
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
                    actions = actions,
                    showReactionBar = false,
                    reactions = emptyList(),
                    selectedEmojis = emptySet(),
                    maxPanelWidth = maxPanelWidth,
                    onReactionClick = { _, _ -> },
                    onMoreEmojiClick = { },
                    onActionClick = {} 
                )
            }
        }

        
        if (isMeasured && popupWidth > 0 && popupHeight > 0) {
            
            val x = ((screenWidth - popupWidth) / 2).coerceIn(edgePaddingPx, screenWidth - popupWidth - edgePaddingPx)
            
            
            val selectionHeight = selectionBounds.bottom - selectionBounds.top
            val selectionCenterY = (selectionBounds.top + selectionBounds.bottom) / 2
            
            
            
            
            val y: Int
            
            if (selectionHeight > screenHeight * 0.5) {
                
                y = (screenHeight - popupHeight) / 2
            } else {
                val spaceAbove = selectionBounds.top - edgePaddingPx
                val spaceBelow = screenHeight - selectionBounds.bottom - edgePaddingPx
                
                val idealYAbove = selectionBounds.top - gapPx - popupHeight
                val idealYBelow = selectionBounds.bottom + gapPx
                
                val canFitAbove = spaceAbove >= popupHeight + gapPx
                val canFitBelow = spaceBelow >= popupHeight + gapPx
                
                when {
                    canFitAbove -> {
                        
                        y = idealYAbove.coerceAtLeast(edgePaddingPx)
                    }
                    canFitBelow -> {
                        
                        y = idealYBelow.coerceAtMost(screenHeight - popupHeight - edgePaddingPx)
                    }
                    else -> {
                        
                        if (spaceAbove >= spaceBelow) {
                            y = edgePaddingPx
                        } else {
                            y = screenHeight - popupHeight - edgePaddingPx
                        }
                    }
                }
            }
            
            
            onPopupPositioned(Rect(x, y, x + popupWidth, y + popupHeight))
            
            
            val originY = when {
                y + popupHeight <= selectionBounds.top -> 1f
                y >= selectionBounds.bottom -> 0f
                else -> 0.5f
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(x, y) }
                    .floatingMenuEntrance(TransformOrigin(0.5f, originY))
            ) {
                MessageActionContent(
                    actions = actions,
                    showReactionBar = false,
                    reactions = emptyList(),
                    selectedEmojis = emptySet(),
                    maxPanelWidth = maxPanelWidth,
                    onReactionClick = { _, _ -> },
                    onMoreEmojiClick = { },
                    onActionClick = onActionClick
                )
            }
        }
    }
}
