package com.difft.android.base.ui

import com.difft.android.base.ui.compose.cornerCutoutPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import com.difft.android.base.ui.compose.FloatingIconButton
import com.difft.android.base.ui.compose.FloatingIconButtonEdge
import com.difft.android.base.ui.compose.FloatingIconButtonTitleGap
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.sidechrome.PublishSideChrome
import com.difft.android.base.ui.sidechrome.SideChromeModel
import com.difft.android.base.ui.theme.DifftTheme














@Composable
fun TitleBar(
    titleText: String,
    modifier: Modifier = Modifier,
    titleEndText: String = "",
    showBackButton: Boolean = true,
    onBackClick: () -> Unit = {}
) {
    val backOnSideBar = PublishSideChrome(SideChromeModel(back = onBackClick.takeIf { showBackButton }))
    Row(
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            
            .cornerCutoutPadding()
    ) {
        if (showBackButton && !backOnSideBar) {
            FloatingIconButton(
                iconRes = R.drawable.chative_ic_back,
                contentDescription = "Back",
                onClick = onBackClick,
                modifier = Modifier.padding(start = FloatingIconButtonEdge, end = FloatingIconButtonTitleGap),
            )
        } else {
            
            Spacer(modifier = Modifier.width(16.dp))
        }

        Text(
            modifier = Modifier.weight(1f, false),
            text = titleText,
            style = DifftTheme.typography.h4,
            color = DifftTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (titleEndText.isNotEmpty()) {
            Text(
                text = titleEndText,
                style = DifftTheme.typography.h4,
                color = DifftTheme.colors.textPrimary,
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
