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
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens

/** Library "Basic dialog": 28 radius, 24 side/top padding, 16 title->body, 24 body->action pill. */
private val DialogCornerRadius = 28.dp
private val DialogPadding = 24.dp
private val TitleToBodyGap = 16.dp

/**
 * Action row insets. The pill sits in a 48dp touch box, so the row itself carries `spec - 4dp`
 * vertically: body -> pill 24 (20 + 4), pill -> bottom 12 (8 + 4). Horizontally the box adds
 * nothing, so the end padding is the full 24.
 */
private val ActionsTopPadding = 20.dp
private val ActionsBottomPadding = 8.dp
private val ActionsEndPadding = DialogPadding
private val ActionsStartPadding = 8.dp
private val ActionsGap = 8.dp

/** Text button: 40dp pill (14/20 label + 10dp) inside a 48dp touch box, 16dp horizontal. */
private val ActionTouchTargetHeight = 48.dp
private val ActionHorizontalPadding = 16.dp
private val ActionVerticalPadding = 10.dp

/**
 * 消息对话框 (替换MessageDialog)
 * 支持自定义Compose内容、按钮颜色等功能
 *
 * Laid out on [BasicAlertDialog] rather than Material's `AlertDialog`: the library dialog puts the
 * action pill 12dp above the bottom edge, which M3's fixed 24dp container padding cannot express.
 */
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
    /** Gates the confirm button, e.g. a dialog whose [content] collects a required input. */
    confirmEnabled: Boolean = true,
    /** Gates the cancel button, e.g. while the confirm action is still in flight. */
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
        val confirmColor = DifftTheme.colors.textInfo
        val disabledColor = DifftTheme.colors.textDisabled
        val pressedColor = DifftTheme.colors.backgroundTertiary

        // 使用系统文字资源
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
            Surface(
                shape = RoundedCornerShape(DialogCornerRadius),
                color = bgColor
            ) {
                Column {
                    // Text block owns no bottom padding; the action row carries the 24dp gap.
                    DialogTextContent(
                        modifier = Modifier.weight(1f, fill = false),
                        title = title,
                        titleColor = titleColor,
                        message = message,
                        contentColor = contentColor,
                        content = content
                    )

                    // FlowRow, not Row: two long labels wrap instead of clipping (M3 parity).
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

/**
 * Title (`h5`) and body (`p3`) block: 24dp on the sides and top, no bottom padding — the action
 * row below carries the whole 24dp gap.
 */
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
        // 如果有自定义内容，显示自定义内容；否则显示消息
        if (content != null) {
            // Match the body defaults the M3 text slot used to provide.
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
            // 空内容
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Library text button: full-radius pill, `p3` (14/20 Regular) label, and a [pressedColor] state
 * layer instead of Material's ripple.
 */
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
    // The whole 48dp box is the touch target; only the pill inside shows the pressed layer.
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

/**
 * Dims the dialog window to the shared modal scrim ([ColorTokens.ScrimDimAmount]); without it the
 * platform theme decides, which is ~60% and differs from the bottom sheets.
 */
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
