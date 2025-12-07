package com.difft.android.base.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.R

/**
 * Title bar with back button and title text.
 * @param titleText the title text
 * @param titleEndText title additional text that always shows no matter has enough space or not
 * @param onBackClick the back button click listener
 */
@Composable
fun TitleBar(titleText: String, titleEndText: String = "", modifier: Modifier = Modifier ,onBackClick: () -> Unit = {}) {
    Row(
        horizontalArrangement =  Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(colorResource(id = R.color.bg1))
    ) {

        IconButton(
            onClick = { onBackClick() },
        ) {
            Icon(
                painter = painterResource(id = R.drawable.chative_ic_back),
                contentDescription = "Back",
                tint = colorResource(id = R.color.t_primary)
            )
        }

        Text(
            modifier = Modifier.weight(1f, false),
            text = titleText,
            color = colorResource(id = R.color.t_primary),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = titleEndText,
            color = colorResource(id = R.color.t_primary),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}

@Preview
@Composable
private fun TitleBarPreview() {
    TitleBar(
        titleText = "I am Page Title XXXXXXXXXXXXXXXXXX",
        titleEndText = "(10)",
        onBackClick = {})
}
@Preview
@Composable
private fun TitleBarPreviewNoExceed() {
    TitleBar(
        titleText = "I am Page Title",
        titleEndText = "(10)",
        onBackClick = {})
}
