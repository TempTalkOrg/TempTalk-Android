package com.difft.android.base.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.difft.android.base.R
import com.difft.android.base.ui.compose.DialogStatusBarSettingEffect
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens


private val DialogCornerRadius = 28.dp
private val DialogPadding = 24.dp
private val TitleToBodyGap = 16.dp






private val ActionsTopPadding = 20.dp
private val ActionsBottomPadding = 8.dp
private val ActionsEndPadding = DialogPadding
private val ActionsStartPadding = 8.dp
private val ActionsGap = 8.dp


private val ActionTouchTargetHeight = 48.dp
private val ActionHorizontalPadding = 16.dp
private val ActionVerticalPadding = 10.dp





@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDialog(
    isVisible: Boolean,
    title: String,
    message: String = "",
    confirmText: String = "",
    cancelText: String = "",
    showCancel: Boolean = true,
    cancelable: Boolean = true,
    confirmButtonColor: Color? = null,
    cancelButtonColor: Color? = null,
    
    confirmEnabled: Boolean = true,
    
    cancelEnabled: Boolean = true,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onDismiss: () -> Unit = {},
    content: (@Composable () -> Unit)? = null
) {
    if (isVisible) {
        val bgColor = DifftTheme.colors.backgroundPopup
        val titleColor = DifftTheme.colors.textPrimary
        val contentColor = DifftTheme.colors.textSecondary
        val cancelColor = DifftTheme.colors.textPrimary
        val confirmColor = DifftTheme.colors.primary
        val disabledColor = DifftTheme.colors.textDisabled
        val pressedColor = DifftTheme.colors.fill

        val defaultConfirmText = confirmText.ifEmpty {
            stringResource(android.R.string.ok)
        }

        val defaultCancelText = cancelText.ifEmpty {
            stringResource(android.R.string.cancel)
        }

        val actualConfirmColor = confirmButtonColor ?: confirmColor
        val actualCancelColor = cancelButtonColor ?: cancelColor

        BasicAlertDialog(
            onDismissRequest = if (cancelable) onDismiss else {
                { }
            }
        ) {
            DialogScrimEffect()
            DialogAnchor.Effect()
            DialogStatusBarSettingEffect()
            Surface(
                shape = RoundedCornerShape(DialogCornerRadius),
                color = bgColor
            ) {
                Column {
                    
                    DialogTextContent(
                        modifier = Modifier.weight(1f, fill = false),
                        title = title,
                        titleColor = titleColor,
                        message = message,
                        contentColor = contentColor,
                        content = content
                    )

                    
                    FlowRow(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(
                                start = ActionsStartPadding,
                                top = ActionsTopPadding,
                                end = ActionsEndPadding,
                                bottom = ActionsBottomPadding
                            ),
                        horizontalArrangement = Arrangement.spacedBy(ActionsGap, Alignment.End)
                    ) {
                        if (showCancel) {
                            DialogTextButton(
                                text = defaultCancelText,
                                color = actualCancelColor,
                                disabledColor = disabledColor,
                                pressedColor = pressedColor,
                                enabled = cancelEnabled,
                                onClick = onCancel
                            )
                        }
                        DialogTextButton(
                            text = defaultConfirmText,
                            color = actualConfirmColor,
                            disabledColor = disabledColor,
                            pressedColor = pressedColor,
                            enabled = confirmEnabled,
                            onClick = onConfirm
                        )
                    }
                }
            }
        }
    }
}





@Composable
private fun DialogTextContent(
    modifier: Modifier,
    title: String,
    titleColor: Color,
    message: String,
    contentColor: Color,
    content: (@Composable () -> Unit)?
) {
    Column(
        modifier = modifier.padding(start = DialogPadding, end = DialogPadding, top = DialogPadding)
    ) {
        val hasBody = content != null || message.isNotEmpty()
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = DifftTheme.typography.h5,
                color = titleColor
            )
            if (hasBody) {
                Spacer(modifier = Modifier.height(TitleToBodyGap))
            }
        }
        if (content != null) {
            
            CompositionLocalProvider(
                LocalContentColor provides contentColor,
                LocalTextStyle provides DifftTheme.typography.p3
            ) {
                content()
            }
        } else if (message.isNotEmpty()) {
            Text(
                text = message,
                style = DifftTheme.typography.p3,
                color = contentColor
            )
        } else if (title.isEmpty()) {
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}





@Composable
private fun DialogTextButton(
    text: String,
    color: Color,
    disabledColor: Color,
    pressedColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    Box(
        modifier = Modifier
            .height(ActionTouchTargetHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (isPressed) pressedColor else Color.Transparent)
                .padding(horizontal = ActionHorizontalPadding, vertical = ActionVerticalPadding)
        ) {
            Text(
                text = text,
                style = DifftTheme.typography.p3,
                color = if (enabled) color else disabledColor
            )
        }
    }
}





@Composable
private fun DialogScrimEffect() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(ColorTokens.ScrimDimAmount)
        onDispose { }
    }
}

@Preview(showBackground = true, name = "Message Dialog Component Preview")
@Composable
fun MessageDialogComponentPreview() {
    MessageDialog(
        isVisible = true,
        title = stringResource(R.string.preview_message_dialog_title),
        message = stringResource(R.string.preview_message_dialog_content),
        confirmText = stringResource(R.string.preview_dialog_confirm),
        cancelText = stringResource(R.string.preview_dialog_cancel),
        showCancel = true,
        onConfirm = { },
        onCancel = { },
        onDismiss = { }
    )
}
