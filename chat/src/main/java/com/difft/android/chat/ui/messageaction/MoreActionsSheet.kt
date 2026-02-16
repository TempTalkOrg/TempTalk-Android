package com.difft.android.chat.ui.messageaction

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.DifftThemePreview
import com.difft.android.base.widget.ComposeDialog

/**
 * Shows a bottom sheet with more action items
 */
object MoreActionsSheet {
    
    /**
     * Show the more actions bottom sheet
     * 
     * @param activity The activity context
     * @param actions List of actions to display
     * @param onActionClick Callback when an action is clicked
     * @param onDismiss Callback when sheet is dismissed
     * @return ComposeDialog for dismissing programmatically
     */
    @OptIn(ExperimentalMaterial3Api::class)
    fun show(
        activity: Activity,
        actions: List<MessageAction>,
        onActionClick: (MessageAction) -> Unit,
        onDismiss: () -> Unit = {}
    ): ComposeDialog {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        
        // Save original window background before adding ComposeView
        val originalBackground = activity.window.decorView.background
        
        val dialog = object : ComposeDialog {
            override fun dismiss() {
                rootView.removeView(composeView)
            }
        }
        
        composeView.setContent {
            DifftTheme {
                var showSheet by remember { mutableStateOf(true) }
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                
                if (showSheet) {
                    ModalBottomSheet(
                        onDismissRequest = {
                            showSheet = false
                            rootView.removeView(composeView)
                            onDismiss()
                        },
                        sheetState = sheetState,
                        dragHandle = { BottomSheetDefaults.DragHandle() },
                        containerColor = DifftTheme.colors.backgroundBottomSheet,
                        contentWindowInsets = { BottomSheetDefaults.windowInsets }
                    ) {
                        MoreActionsContent(
                            actions = actions,
                            onActionClick = { action ->
                                showSheet = false
                                rootView.removeView(composeView)
                                onActionClick(action)
                            }
                        )
                    }
                }
            }
        }
        
        rootView.addView(composeView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        
        // Immediately restore window background after adding ComposeView
        if (originalBackground != null) {
            activity.window.setBackgroundDrawable(originalBackground)
        }
        
        return dialog
    }
}

/**
 * Content composable for the more actions sheet
 */
@Composable
fun MoreActionsContent(
    actions: List<MessageAction>,
    onActionClick: (MessageAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        actions.forEach { action ->
            MoreActionItem(
                action = action,
                onClick = { onActionClick(action) }
            )
        }
        
        // Bottom spacing for navigation bar
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Single action item in the more sheet
 */
@Composable
private fun MoreActionItem(
    action: MessageAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Use textPrimary for both icon and text (consistent with popup menu)
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
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(action.labelRes),
            fontSize = 16.sp,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============== Previews ==============

@Preview(showBackground = true)
@Composable
private fun MoreActionsContentPreview() {
    DifftThemePreview {
        MoreActionsContent(
            actions = listOf(
                MessageAction.speechToText(),
                MessageAction.save(isMediaFile = true),
                MessageAction.multiSelect(),
                MessageAction.saveToNote(),
                MessageAction.recall(),
                MessageAction.moreInfo()
            ),
            onActionClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Dark Theme")
@Composable
private fun MoreActionsContentDarkPreview() {
    DifftThemePreview(darkTheme = true) {
        MoreActionsContent(
            actions = listOf(
                MessageAction.save(isMediaFile = false),
                MessageAction.multiSelect(),
                MessageAction.saveToNote(),
                MessageAction.deleteSaved(),
                MessageAction.recall(),
                MessageAction.moreInfo()
            ),
            onActionClick = {}
        )
    }
}
