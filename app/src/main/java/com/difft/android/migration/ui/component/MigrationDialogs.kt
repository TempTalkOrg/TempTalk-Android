package com.difft.android.migration.ui.component

import androidx.compose.runtime.Composable
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.MessageDialog






@Composable
fun MigrationConfirmDialog(
    title: String,
    message: String,
    keepText: String,
    goText: String,
    onKeep: () -> Unit,
    onGo: () -> Unit,
    goDanger: Boolean = false,
) {
    MessageDialog(
        isVisible = true,
        title = title,
        message = message,
        confirmText = goText,
        cancelText = keepText,
        confirmButtonColor = if (goDanger) DifftTheme.colors.textError else null,
        onConfirm = onGo,
        onCancel = onKeep,
        onDismiss = onKeep,
    )
}
