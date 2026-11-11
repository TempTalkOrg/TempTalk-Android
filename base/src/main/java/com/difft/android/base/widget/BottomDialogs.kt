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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    showDragHandle: Boolean = true,
    content: @Composable () -> Unit
) {
    if (isVisible) {
        DifftModalBottomSheet(onDismissRequest = onDismiss, showDragHandle = showDragHandle) {
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}


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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenBottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    backgroundDrawable: android.graphics.drawable.Drawable? = null,
    content: @Composable () -> Unit
) {
    if (isVisible) {
        if (backgroundDrawable != null) {
            DifftModalBottomSheet(
                onDismissRequest = onDismiss,
                showDragHandle = false, 
                containerColor = Color.Transparent,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .drawBehind {
                            backgroundDrawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                            backgroundDrawable.draw(drawContext.canvas.nativeCanvas)
                        }
                ) {
                    
                    
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
