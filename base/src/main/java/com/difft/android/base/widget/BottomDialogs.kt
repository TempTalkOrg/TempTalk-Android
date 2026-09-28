package com.difft.android.base.widget

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.base.R
import com.difft.android.base.ui.compose.DifftDragHandle
import com.difft.android.base.ui.compose.DifftModalBottomSheet
import com.difft.android.base.ui.theme.DifftTheme

/**
 * 普通底部Dialog - 支持Compose组件
 * 注意：ModalBottomSheet 设计上就是可关闭的
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    if (isVisible) {
        DifftModalBottomSheet(onDismissRequest = onDismiss) {
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

/**
 * 普通底部Dialog - 支持自定义View (重载)
 * 注意：ModalBottomSheet 设计上就是可关闭的
 */
@Composable
fun BottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    layoutId: Int,
    onViewCreated: (View) -> Unit = {}
) {
    BottomDialog(
        isVisible = isVisible,
        onDismiss = onDismiss,
        content = {
            AndroidView(
                factory = { context ->
                    android.view.LayoutInflater.from(context).inflate(layoutId, null)
                },
                modifier = Modifier.fillMaxWidth(),
                update = { view ->
                    onViewCreated(view)
                }
            )
        }
    )
}

/**
 * 全屏底部Dialog - 支持Compose组件
 * 注意：ModalBottomSheet 设计上就是可关闭的
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenBottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    backgroundDrawable: android.graphics.drawable.Drawable? = null,
    content: @Composable () -> Unit
) {
    if (isVisible) {
        // 如果有自定义背景，我们需要自定义整个 ModalBottomSheet 的外观
        if (backgroundDrawable != null) {
            DifftModalBottomSheet(
                onDismissRequest = onDismiss,
                showDragHandle = false, // 移除默认手势指示器，让背景完全覆盖
                containerColor = Color.Transparent,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .drawBehind {
                            // ChatBackgroundDrawable 自动计算边框，直接覆盖整个区域
                            backgroundDrawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                            backgroundDrawable.draw(drawContext.canvas.nativeCanvas)
                        }
                ) {
                    // Handle above the content, not under it: a Column keeps the shared handle
                    // visible even when the sheet content paints an opaque background.
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            DifftDragHandle(color = DifftTheme.colors.textDisabled)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            content()
                        }
                    }
                }
            }
        } else {
            // 使用纯色背景的原有逻辑
            DifftModalBottomSheet(onDismissRequest = onDismiss) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 全屏底部Dialog - 支持自定义View (重载)
 * 注意：ModalBottomSheet 设计上就是可关闭的
 */
@Composable
fun FullScreenBottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    backgroundDrawable: android.graphics.drawable.Drawable? = null,
    layoutId: Int,
    onViewCreated: (View) -> Unit = {}
) {
    FullScreenBottomDialog(
        isVisible = isVisible,
        onDismiss = onDismiss,
        backgroundDrawable = backgroundDrawable,
        content = {
            AndroidView(
                factory = { context ->
                    android.view.LayoutInflater.from(context).inflate(layoutId, null)
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    onViewCreated(view)
                }
            )
        }
    )
}

@Preview(showBackground = true, name = "Full Screen Bottom Dialog Component Preview")
@Composable
fun FullScreenBottomDialogComponentPreview() {
    FullScreenBottomDialog(
        isVisible = true,
        onDismiss = { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.preview_fullscreen_dialog_title),
                style = DifftTheme.typography.h3,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = stringResource(R.string.preview_fullscreen_dialog_content),
                style = DifftTheme.typography.p2,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Preview(showBackground = true, name = "Bottom Dialog Component Preview")
@Composable
fun BottomDialogComponentPreview() {
    BottomDialog(
        isVisible = true,
        onDismiss = { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.preview_bottom_dialog_title),
                style = DifftTheme.typography.h4,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = stringResource(R.string.preview_bottom_dialog_content),
                style = DifftTheme.typography.p2,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}
