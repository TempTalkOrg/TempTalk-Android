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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.base.utils.dp as dpToPx

/**
 * Popup for failed message actions (resend/delete)
 * Uses same style as MessageActionPopup
 * 
 * Note: Uses full-screen overlay with Box+offset instead of Compose Popup
 * to avoid affecting the window background drawable
 */
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
    
    /**
     * Show the failed message action popup
     * 
     * @param anchorView The view to anchor the popup to (usually the message bubble)
     * @param message The failed message
     * @param containerView Optional container view to determine available bounds (e.g., RecyclerView)
     * @param callbacks Callbacks for user interactions
     */
    fun show(
        anchorView: View,
        message: TextChatMessage,
        containerView: View? = null,
        callbacks: Callbacks
    ) {
        // Dismiss any existing popup
        dismissInternal(notifyCallback = false)
        
        this.currentMessage = message
        this.callbacks = callbacks
        this._isShowing = true
        
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        
        // Get anchor bounds
        val anchorBounds = Rect()
        anchorView.getGlobalVisibleRect(anchorBounds)
        
        // Get the actual visible content area from container or fallback to rootView
        val contentBounds = Rect()
        (containerView ?: rootView).getGlobalVisibleRect(contentBounds)
        
        // Get rootView bounds to calculate relative positions
        val rootBounds = Rect()
        rootView.getGlobalVisibleRect(rootBounds)
        
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
        
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            
            setContent {
                DifftTheme(applyWindowBackground = false) {
                    var showPopup by remember { mutableStateOf(true) }
                    
                    // Handle back press
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
        
        // Full-screen overlay
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

/**
 * Full-screen overlay that positions the popup content using Box + offset.
 * Renders the shared [MessageActionContent] with the failed-state action set (resend / delete),
 * no reaction bar, and always right-aligned positioning (INV-5).
 */
@Composable
private fun FailedMessagePopupOverlay(
    anchorBounds: Rect,
    contentBounds: Rect,
    onResendClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onOutsideClick: () -> Unit
) {
    val density = LocalDensity.current
    var popupWidth by remember { mutableIntStateOf(0) }
    var popupHeight by remember { mutableIntStateOf(0) }
    val isMeasured = popupWidth > 0 && popupHeight > 0

    // Constants (pixels)
    val edgePaddingPx = 8.dpToPx
    val arrowHeightPx = 6.dpToPx
    val arrowGapPx = 2.dpToPx

    // Panel width guard (decision #11) — same value for measure and display phases (INV-1)
    val maxPanelWidth = remember(contentBounds, edgePaddingPx) {
        computeMaxPanelWidth(contentBounds.width(), edgePaddingPx, density)
    }

    // Failed-state action set (Master Order B5): Resend -> Delete(red)
    val failedActions = remember { buildFailedActions() }

    // Unified type dispatcher for the shared content
    val onActionClick: (MessageAction) -> Unit = { action ->
        failedActionDispatch(action.type, onResendClick, onDeleteClick)
    }

    // Use actual content area bounds
    val minY = contentBounds.top + edgePaddingPx
    val maxY = contentBounds.bottom - edgePaddingPx

    // Full screen transparent overlay to catch outside clicks
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Check if tap is outside popup bounds
                    if (isMeasured) {
                        val x = calculatePopupX(contentBounds, popupWidth, edgePaddingPx)
                        val (y, _) = calculatePopupY(
                            anchorBounds, popupHeight, minY, maxY, arrowGapPx, arrowHeightPx
                        )
                        
                        val isInsidePopup = offset.x >= x && offset.x <= x + popupWidth &&
                                offset.y >= y && offset.y <= y + popupHeight
                        
                        if (!isInsidePopup) {
                            onOutsideClick()
                        }
                    } else {
                        onOutsideClick()
                    }
                }
            }
    ) {
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
                    actions = failedActions,
                    showReactionBar = false,
                    reactions = emptyList(),
                    selectedEmojis = emptySet(),
                    maxPanelWidth = maxPanelWidth,
                    arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = 60.dp),
                    onReactionClick = { _, _ -> },
                    onMoreEmojiClick = { },
                    onActionClick = { }
                )
            }
        }
        
        // Display phase: positioned content
        if (isMeasured) {
            val x = calculatePopupX(contentBounds, popupWidth, edgePaddingPx)
            val (y, isBelow) = calculatePopupY(
                anchorBounds, popupHeight, minY, maxY, arrowGapPx, arrowHeightPx
            )
            
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
                    actions = failedActions,
                    showReactionBar = false,
                    reactions = emptyList(),
                    selectedEmojis = emptySet(),
                    maxPanelWidth = maxPanelWidth,
                    arrowConfig = ArrowConfig(isBelow = isBelow, arrowOffsetX = arrowOffsetX),
                    onReactionClick = { _, _ -> },
                    onMoreEmojiClick = { },
                    onActionClick = onActionClick
                )
            }
        }
    }
}

private fun calculatePopupX(contentBounds: Rect, popupWidth: Int, edgePaddingPx: Int): Int {
    // Outgoing messages: align right within content area
    return contentBounds.right - popupWidth - edgePaddingPx
}

private fun calculatePopupY(
    anchorBounds: Rect,
    popupHeight: Int,
    minY: Int,
    maxY: Int,
    arrowGapPx: Int,
    arrowHeightPx: Int
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

/** Failed-state action set (Master Order B5): Resend -> Delete(red). */
internal fun buildFailedActions(): List<MessageAction> =
    listOf(MessageAction.resend(), MessageAction.delete())

/**
 * Maps a failed-state action type to its callback; unknown types are ignored (else-safe).
 *
 * `MORE_INFO` is intentionally NOT dispatched here: a failed message has not reached anyone, so the
 * delivery/read detail page carries no information for it. The regular (non-failed) message menu
 * keeps its Info entry — only this failed-state set is narrowed.
 */
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

