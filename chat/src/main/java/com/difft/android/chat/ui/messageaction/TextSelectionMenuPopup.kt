package com.difft.android.chat.ui.messageaction

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.DifftThemePreview
import com.difft.android.base.utils.dp as dpToPx
import com.difft.android.chat.R

/**
 * Text selection menu popup
 * Shows Copy, Forward, Translate, Select All options when text is selected
 * Adds ComposeView to Activity's content view for proper lifecycle support
 */
class TextSelectionMenuPopup(
    private val activity: FragmentActivity
) {
    
    /**
     * Actions available for text selection
     */
    enum class Action {
        COPY,
        FORWARD,
        TRANSLATE,
        SELECT_ALL
    }
    
    /**
     * Callbacks for menu interactions
     */
    interface Callbacks {
        fun onCopy(selectedText: String)
        fun onForward(selectedText: String)
        fun onTranslate(selectedText: String)
        fun onSelectAll()
        fun onDismiss()
    }
    
    private var composeView: ComposeView? = null
    private var containerView: FrameLayout? = null
    private var callbacks: Callbacks? = null
    private var currentSelectedText: String = ""
    private var _isShowing = false
    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0
    
    /**
     * Show the text selection menu
     * 
     * @param anchorView The view to anchor the popup to
     * @param selectedText The currently selected text
     * @param touchX X position to show the menu at (screen coordinates)
     * @param touchY Y position to show the menu at (screen coordinates)
     * @param callbacks Callbacks for user interactions
     */
    fun show(
        anchorView: View,
        selectedText: String,
        touchX: Int,
        touchY: Int,
        callbacks: Callbacks
    ) {
        dismiss()
        
        this.callbacks = callbacks
        this.currentSelectedText = selectedText
        this.lastTouchX = touchX
        this.lastTouchY = touchY
        this._isShowing = true
        
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        
        val newComposeView = ComposeView(activity).apply {
            setContent {
                DifftTheme {
                    val density = LocalDensity.current
                    var offsetX by remember { mutableIntStateOf(0) }
                    var offsetY by remember { mutableIntStateOf(0) }
                    
                    // Transparent overlay that doesn't block touches (for text selection to continue)
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset { IntOffset(offsetX, offsetY) }
                                .onGloballyPositioned { coordinates ->
                                    val (x, y) = calculatePosition(
                                        touchX,
                                        touchY,
                                        coordinates.size.width,
                                        coordinates.size.height
                                    )
                                    offsetX = x
                                    offsetY = y
                                }
                        ) {
                            TextSelectionMenuContent(
                                onCopy = {
                                    this@TextSelectionMenuPopup.callbacks?.onCopy(currentSelectedText)
                                    dismiss()
                                },
                                onForward = {
                                    this@TextSelectionMenuPopup.callbacks?.onForward(currentSelectedText)
                                    dismiss()
                                },
                                onTranslate = {
                                    this@TextSelectionMenuPopup.callbacks?.onTranslate(currentSelectedText)
                                    dismiss()
                                },
                                onSelectAll = {
                                    this@TextSelectionMenuPopup.callbacks?.onSelectAll()
                                }
                            )
                        }
                    }
                }
            }
        }
        
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Don't intercept touches - allow text selection to continue
            isClickable = false
            isFocusable = false
            addView(newComposeView)
        }
        
        rootView.addView(container)
        
        this.composeView = newComposeView
        this.containerView = container
    }
    
    /**
     * Update the selected text (when selection changes)
     */
    fun updateSelectedText(selectedText: String) {
        this.currentSelectedText = selectedText
    }
    
    /**
     * Dismiss the popup
     */
    fun dismiss() {
        containerView?.let { container ->
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
            rootView?.removeView(container)
        }
        containerView = null
        composeView = null
        
        if (_isShowing) {
            _isShowing = false
            callbacks?.onDismiss()
        }
        cleanup()
    }
    
    /**
     * Check if popup is currently showing
     */
    val isShowing: Boolean
        get() = _isShowing
    
    private fun calculatePosition(
        touchX: Int,
        touchY: Int,
        popupWidth: Int,
        popupHeight: Int
    ): Pair<Int, Int> {
        val screenWidth = com.difft.android.base.utils.WindowSizeClassUtil.getWindowWidthPx(activity)
        val screenHeight = com.difft.android.base.utils.WindowSizeClassUtil.getWindowHeightPx(activity)
        val padding = 16.dpToPx
        val verticalOffset = 20.dpToPx  // Space between selection and menu
        
        // Horizontal: center on touch point
        var x = touchX - popupWidth / 2
        val minX = padding
        val maxX = (screenWidth - popupWidth - padding).coerceAtLeast(minX)
        x = x.coerceIn(minX, maxX)
        
        // Vertical: above touch point
        var y = touchY - popupHeight - verticalOffset
        
        // If not enough space above, show below
        if (y < padding) {
            y = touchY + verticalOffset
        }
        
        // Ensure Y is within screen bounds
        val minY = padding
        val maxY = (screenHeight - popupHeight - padding).coerceAtLeast(minY)
        y = y.coerceIn(minY, maxY)
        
        return x to y
    }
    
    private fun cleanup() {
        callbacks = null
        currentSelectedText = ""
    }
}

/**
 * Content composable for text selection menu
 */
@Composable
fun TextSelectionMenuContent(
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onTranslate: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = DifftTheme.colors.backgroundPopup
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionActionButton(
                iconRes = R.drawable.chat_message_action_copy,
                label = stringResource(R.string.chat_message_action_copy),
                onClick = onCopy
            )
            SelectionActionButton(
                iconRes = R.drawable.chat_message_action_forward,
                label = stringResource(R.string.chat_message_action_forward),
                onClick = onForward
            )
            SelectionActionButton(
                iconRes = R.drawable.chat_message_action_translate,
                label = stringResource(R.string.chat_message_action_translate),
                onClick = onTranslate
            )
            SelectionActionButton(
                iconRes = R.drawable.chat_message_action_select_all,
                label = stringResource(R.string.chat_message_action_select_all),
                onClick = onSelectAll
            )
        }
    }
}

@Composable
private fun SelectionActionButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = DifftTheme.colors.textPrimary
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = DifftTheme.colors.textPrimary
        )
    }
}

// ============== Previews ==============

@Preview(showBackground = true)
@Composable
private fun TextSelectionMenuContentPreview() {
    DifftThemePreview {
        TextSelectionMenuContent(
            onCopy = {},
            onForward = {},
            onTranslate = {},
            onSelectAll = {}
        )
    }
}

@Preview(showBackground = true, name = "Dark Theme")
@Composable
private fun TextSelectionMenuContentDarkPreview() {
    DifftThemePreview(darkTheme = true) {
        TextSelectionMenuContent(
            onCopy = {},
            onForward = {},
            onTranslate = {},
            onSelectAll = {}
        )
    }
}
