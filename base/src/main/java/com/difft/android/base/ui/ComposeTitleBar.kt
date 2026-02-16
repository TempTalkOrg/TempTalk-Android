package com.difft.android.base.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Title bar with back button and title text.
 * Uses DifftTheme colors to ensure consistency with app theme.
 *
 * @param titleText the title text
 * @param titleEndText title additional text that always shows no matter has enough space or not
 * @param modifier optional modifier for the title bar
 * @param showBackButton whether to show the back button (false in dual-pane mode)
 * @param onBackClick the back button click listener
 */
@Composable
fun TitleBar(
    titleText: String,
    titleEndText: String = "",
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    onBackClick: () -> Unit = {}
) {
    Row(
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        if (showBackButton) {
            IconButton(
                onClick = { onBackClick() },
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.chative_ic_back),
                    contentDescription = "Back",
                    tint = DifftTheme.colors.textPrimary
                )
            }
        } else {
            // Add padding when back button is hidden
            Spacer(modifier = Modifier.width(16.dp))
        }

        Text(
            modifier = Modifier.weight(1f, false),
            text = titleText,
            color = DifftTheme.colors.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (titleEndText.isNotEmpty()) {
            Text(
                text = titleEndText,
                color = DifftTheme.colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

@Preview
@Composable
private fun TitleBarPreview() {
    DifftTheme {
        TitleBar(
            titleText = "I am Page Title XXXXXXXXXXXXXXXXXX",
            titleEndText = "(10)",
            onBackClick = {})
    }
}

@Preview
@Composable
private fun TitleBarPreviewNoExceed() {
    DifftTheme {
        TitleBar(
            titleText = "I am Page Title",
            titleEndText = "(10)",
            onBackClick = {})
    }
}
