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
import com.difft.android.base.ui.compose.DialogStatusBarSettingEffect
import com.difft.android.base.ui.theme.DifftTheme

@Composable
fun WaitDialog(
    isVisible: Boolean,
    message: String = "",
    cancelable: Boolean = true,
    layoutId: Int? = null,
    onViewCreated: (View) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    if (isVisible) {
        val bgColor = DifftTheme.colors.backgroundPopup
        val textColor = DifftTheme.colors.textPrimary
        val progressColor = DifftTheme.colors.primary

        Dialog(
            onDismissRequest = if (cancelable) onDismiss else {
                { }
            }
        ) {
            DialogAnchor.Effect()
            DialogStatusBarSettingEffect()
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
                            .padding(if (message.isNotEmpty()) 24.dp else 20.dp),
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
