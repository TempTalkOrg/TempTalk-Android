package com.difft.android.migration.ui.component

import androidx.compose.runtime.Composable
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.MessageDialog

/**
 * Confirmation dialog for the migration flows, on the app's standard message dialog: the "stay"
 * button at the start and the action at the end. [goDanger] paints the action red when it discards
 * something.
 */
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
