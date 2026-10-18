package com.difft.android.setting

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
 * caption of the row above. Shared by the notification and background-connection screens.
 */
@Composable
internal fun SetupGuideFooter(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkColor = DifftTheme.colors.textInfo
    val text = buildAnnotatedString {
        append(stringResource(ChatR.string.background_connection_guide_prompt))
        append(" ")
        withLink(
            LinkAnnotation.Clickable(
                tag = "setup_guide",
                styles = TextLinkStyles(
                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                ),
            ) { onClick() },
        ) {
            append(stringResource(ChatR.string.background_connection_guide_link))
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
