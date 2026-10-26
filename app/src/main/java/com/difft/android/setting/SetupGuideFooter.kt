package com.difft.android.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R as ChatR

/**
 * #1328: page-level prompt with a trailing link to the notification setup guide, styled like the
 * proxy "Learn more" footer. Centred and set apart at the page end so it does not read as the
 * caption of the row above. The notification screen also passes [onTest] for the notification test
 * link; the background connection screen passes its own [prompt] suggesting a one-time check (#1350).
 */
@Composable
internal fun SetupGuideFooter(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onTest: (() -> Unit)? = null,
    @StringRes prompt: Int = ChatR.string.background_connection_guide_prompt,
) {
    val linkColor = DifftTheme.colors.textInfo
    val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    val text = buildAnnotatedString {
        append(stringResource(prompt))
        append(" ")
        withLink(LinkAnnotation.Clickable(tag = "setup_guide", styles = linkStyles) { onClick() }) {
            append(stringResource(ChatR.string.background_connection_guide_link))
        }
        if (onTest != null) {
            append(stringResource(ChatR.string.notification_test_link_separator))
            withLink(LinkAnnotation.Clickable(tag = "notification_test", styles = linkStyles) { onTest() }) {
                append(stringResource(ChatR.string.notification_test_link))
            }
        }
    }
    Text(
        text = text,
        style = DifftTheme.typography.p3,
        color = DifftTheme.colors.textTertiary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 24.dp),
    )
}
