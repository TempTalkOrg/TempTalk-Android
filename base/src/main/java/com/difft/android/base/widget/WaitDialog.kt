package com.difft.android.base.widget

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme

/**
 * 等待对话框 (替换WaitDialog)
 */
@Composable
fun WaitDialog(
    isVisible: Boolean,
    message: String = "", // 默认不显示文字
    cancelable: Boolean = true,
    layoutId: Int? = null,
    onViewCreated: (View) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    if (isVisible) {
        val bgColor = DifftTheme.colors.backgroundPopup
        val textColor = DifftTheme.colors.textPrimary
        val progressColor = DifftTheme.colors.textInfo

        Dialog(
            onDismissRequest = if (cancelable) onDismiss else {
                { }
            }
        ) {
            // 如果有自定义布局，使用透明背景
            if (layoutId != null) {
                AndroidView(
                    factory = { context ->
                        android.view.LayoutInflater.from(context).inflate(layoutId, null)
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        onViewCreated(view)
                    }
                )
            } else {
                // 默认的等待对话框
                Card(
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(16.dp),
                    shape = DifftTheme.radius.largeShape,
                    colors = CardDefaults.cardColors(containerColor = bgColor)
                ) {
                    Column(
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(if (message.isNotEmpty()) 24.dp else 20.dp), // 有文字时用24dp，无文字时用20dp
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = progressColor
                        )
                        if (message.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = message,
                                style = DifftTheme.typography.bodyLarge,
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Wait Dialog Component Preview")
@Composable
fun WaitDialogComponentPreview() {
    WaitDialog(
        isVisible = true,
        message = stringResource(R.string.preview_wait_dialog_message)
    )
}
