package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.DifftThemePreview
import com.difft.android.base.utils.WindowSizeClassUtil
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
        
        val screenWidth = WindowSizeClassUtil.getWindowWidthPx(activity)
        
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            
            setContent {
                DifftTheme {
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
                            screenWidth = screenWidth,
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
        // Save original window background before adding ComposeView
        val originalBackground = activity.window.decorView.background
        
        // Use OnPreDrawListener to restore background before first frame is drawn (avoids flicker)
        rootView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                rootView.viewTreeObserver.removeOnPreDrawListener(this)
                if (originalBackground != null) {
                    activity.window.setBackgroundDrawable(originalBackground)
                }
                return true
            }
        })
        
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

// Item dimensions (Compose Dp)
private val ITEM_WIDTH = 55.dp
private val ITEM_SPACING = 6.dp
private val ARROW_WIDTH = 12.dp
private val ARROW_HEIGHT = 6.dp

/**
 * Full-screen overlay that positions the popup content using Box + offset
 */
@Composable
private fun FailedMessagePopupOverlay(
    anchorBounds: Rect,
    contentBounds: Rect,
    screenWidth: Int,
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
                        val x = calculatePopupX(screenWidth, popupWidth, edgePaddingPx)
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
                FailedMessageActionContent(
                    arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = 60.dp),
                    onResendClick = { },
                    onDeleteClick = { }
                )
            }
        }
        
        // Display phase: positioned content
        if (isMeasured) {
            val x = calculatePopupX(screenWidth, popupWidth, edgePaddingPx)
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
                FailedMessageActionContent(
                    arrowConfig = ArrowConfig(isBelow = isBelow, arrowOffsetX = arrowOffsetX),
                    onResendClick = onResendClick,
                    onDeleteClick = onDeleteClick
                )
            }
        }
    }
}

private fun calculatePopupX(screenWidth: Int, popupWidth: Int, edgePaddingPx: Int): Int {
    // Outgoing messages: align right
    return screenWidth - popupWidth - edgePaddingPx
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

/**
 * Content for failed message action popup
 */
@Composable
fun FailedMessageActionContent(
    arrowConfig: ArrowConfig?,
    onResendClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = DifftTheme.colors.backgroundActionPopup
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        // Arrow on top (when popup is below anchor)
        if (arrowConfig != null && arrowConfig.isBelow) {
            PopupArrow(
                isPointingUp = true,
                offsetX = arrowConfig.arrowOffsetX,
                color = backgroundColor
            )
        }
        
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor
            ),
            elevation = CardDefaults.elevatedCardElevation(
                defaultElevation = 4.dp
            )
        ) {
            Row(
                modifier = Modifier
                    .width(IntrinsicSize.Min)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                verticalAlignment = Alignment.Top
            ) {
                // Resend action
                ActionItem(
                    action = MessageAction.resend(),
                    onClick = onResendClick
                )
                
                // Delete action
                ActionItem(
                    action = MessageAction.delete(),
                    onClick = onDeleteClick
                )
            }
        }
        
        // Arrow on bottom (when popup is above anchor)
        if (arrowConfig != null && !arrowConfig.isBelow) {
            PopupArrow(
                isPointingUp = false,
                offsetX = arrowConfig.arrowOffsetX,
                color = backgroundColor
            )
        }
    }
}

@Composable
private fun ActionItem(
    action: MessageAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconTint = if (action.isDestructive) {
        DifftTheme.colors.error
    } else {
        DifftTheme.colors.textPrimary
    }
    
    val textColor = if (action.isDestructive) {
        DifftTheme.colors.error
    } else {
        DifftTheme.colors.textPrimary
    }
    
    Column(
        modifier = modifier
            .width(ITEM_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(action.labelRes),
            fontSize = 11.sp,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun PopupArrow(
    isPointingUp: Boolean,
    offsetX: Dp,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .offset(x = offsetX - ARROW_WIDTH / 2)
            .size(width = ARROW_WIDTH, height = ARROW_HEIGHT)
    ) {
        val path = Path().apply {
            if (isPointingUp) {
                moveTo(0f, size.height)
                lineTo(size.width / 2, 0f)
                lineTo(size.width, size.height)
                close()
            } else {
                moveTo(0f, 0f)
                lineTo(size.width / 2, size.height)
                lineTo(size.width, 0f)
                close()
            }
        }
        drawPath(path, color)
    }
}

// ============== Previews ==============

@Preview(showBackground = true)
@Composable
private fun FailedMessageActionContentPreview() {
    DifftThemePreview {
        FailedMessageActionContent(
            arrowConfig = ArrowConfig(isBelow = true, arrowOffsetX = 60.dp),
            onResendClick = {},
            onDeleteClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Dark Theme")
@Composable
private fun FailedMessageActionContentDarkPreview() {
    DifftThemePreview(darkTheme = true) {
        FailedMessageActionContent(
            arrowConfig = ArrowConfig(isBelow = false, arrowOffsetX = 60.dp),
            onResendClick = {},
            onDeleteClick = {}
        )
    }
}
