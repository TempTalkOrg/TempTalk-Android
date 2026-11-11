package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.ui.compose.floatingMenuEntrance
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.base.utils.dp as dpToPx








class FailedMessageActionPopup(
    private val activity: FragmentActivity
) {
    
    interface Callbacks {
        fun onResend()
        fun onDelete()
        fun onDismiss()
    }
    
    private var composeView: ComposeView? = null
    private var currentMessage: TextChatMessage? = null
    private var callbacks: Callbacks? = null
    private var _isShowing = false
    
    







    fun show(
        anchorView: View,
        message: TextChatMessage,
        containerView: View? = null,
        callbacks: Callbacks
    ) {
        
        dismissInternal(notifyCallback = false)
        
        this.currentMessage = message
        this.callbacks = callbacks
        this._isShowing = true
        
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        
        val rootBounds = Rect().also { rootView.getGlobalVisibleRect(it) }
        val relativeAnchorBounds = Rect().also { anchorView.getGlobalVisibleRect(it) }.toRootRelative(rootBounds)
        
        val relativeContentBounds = Rect().also { ChatContentBand.onScreen(containerView ?: rootView, it) }
            .toRootRelative(rootBounds)
        
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            
            setContent {
                DifftTheme(applyWindowBackground = false) {
                    var showPopup by remember { mutableStateOf(true) }
                    
                    
                    BackHandler(enabled = showPopup) {
                        showPopup = false
                        notifyDismiss(callbacks)
                    }
                    
                    if (showPopup) {
                        FailedMessagePopupOverlay(
                            anchorBounds = relativeAnchorBounds,
                            contentBounds = relativeContentBounds,
                            onResendClick = {
                                showPopup = false
                                callbacks.onResend()
                                notifyDismiss(callbacks)
                            },
                            onDeleteClick = {
                                showPopup = false
                                callbacks.onDelete()
                                notifyDismiss(callbacks)
                            },
                            onOutsideClick = {
                                showPopup = false
                                notifyDismiss(callbacks)
                            }
                        )
                    }
                }
            }
        }
        
        
        rootView.addView(composeView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    
    private fun notifyDismiss(callbacks: Callbacks) {
        _isShowing = false
        removeComposeView()
        callbacks.onDismiss()
        cleanup()
    }
    
    private fun removeComposeView() {
        composeView?.let { view ->
            view.disposeComposition()
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
            rootView?.removeView(view)
        }
        composeView = null
    }
    
    fun dismiss() {
        dismissInternal(notifyCallback = true)
    }
    
    private fun dismissInternal(notifyCallback: Boolean) {
        val wasShowing = _isShowing
        _isShowing = false
        removeComposeView()
        
        if (wasShowing && notifyCallback) {
            callbacks?.onDismiss()
        }
        cleanup()
    }
    
    val isShowing: Boolean
        get() = _isShowing
    
    val message: TextChatMessage?
        get() = currentMessage
    
    private fun cleanup() {
        currentMessage = null
        callbacks = null
    }
}






@Composable
private fun FailedMessagePopupOverlay(
    anchorBounds: PopupRect,
    contentBounds: PopupRect,
    onResendClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onOutsideClick: () -> Unit
) {
    val density = LocalDensity.current
    var popupWidth by remember { mutableIntStateOf(0) }
    var popupHeight by remember { mutableIntStateOf(0) }
    val isMeasured = popupWidth > 0 && popupHeight > 0
    val metrics = remember { PopupMetrics(edgePaddingPx = 8.dpToPx, arrowGapPx = 2.dpToPx, arrowMinInsetPx = 16.dpToPx) }
    
    var menuRect by remember { mutableStateOf<ComposeRect?>(null) }

    
    val maxPanelWidth = remember(contentBounds) {
        computeMaxPanelWidth(contentBounds.width, metrics.edgePaddingPx, density)
    }

    
    val failedActions = remember { buildFailedActions() }

    
    val onActionClick: (MessageAction) -> Unit = { action ->
        failedActionDispatch(action.type, onResendClick, onDeleteClick)
    }

    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(Unit) {
                
                
                detectTapGestures { offset ->
                    if (menuRect?.contains(offset) != true) onOutsideClick()
                }
            }
    ) {
        
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
                    actions = failedActions,
                    showReactionBar = false,
                    reactions = emptyList(),
                    selectedEmojis = emptySet(),
                    maxPanelWidth = maxPanelWidth,
                    onReactionClick = { _, _ -> },
                    onMoreEmojiClick = { },
                    onActionClick = { }
                )
            }
        } else {
            
            val placement = remember(anchorBounds, contentBounds, popupWidth, popupHeight) {
                MessageActionPopupPositioner.place(
                    SelectionAnchorInput(anchorBounds), contentBounds, popupWidth, popupHeight, isOutgoing = true, metrics
                )
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(placement.x, placement.y) }
                    .onGloballyPositioned { menuRect = it.boundsInRoot() }
                    .testTag(FAILED_POPUP_MENU_TAG)
                    
                    .floatingMenuEntrance(
                        TransformOrigin(
                            pivotFractionX = (placement.arrowOffsetXPx.toFloat() / popupWidth).coerceIn(0f, 1f),
                            pivotFractionY = if (placement.isBelow) 0f else 1f,
                        )
                    )
            ) {
                MessageActionContent(
                    actions = failedActions,
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


internal const val FAILED_POPUP_MENU_TAG = "failed_popup_menu"


internal fun buildFailedActions(): List<MessageAction> =
    listOf(MessageAction.resend(), MessageAction.delete())








internal fun failedActionDispatch(
    type: MessageAction.Type,
    onResend: () -> Unit,
    onDelete: () -> Unit
) {
    when (type) {
        MessageAction.Type.RESEND -> onResend()
        MessageAction.Type.DELETE -> onDelete()
        else -> Unit
    }
}

