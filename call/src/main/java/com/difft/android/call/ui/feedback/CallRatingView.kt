package com.difft.android.call.ui.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.R
import com.difft.android.call.data.FeedbackCallInfo

@Composable
internal fun CallRatingView(
    callInfo: FeedbackCallInfo,
    onRatingChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (CallFeedbackRequestBody) -> Unit,
    showQuestion: () -> Unit
) {
    var rating by remember { mutableIntStateOf(0) }

    val ratingDescriptions = listOf(
        ResUtils.getString(R.string.call_rating_level_very_bad),
        ResUtils.getString(R.string.call_rating_level_bad),
        ResUtils.getString(R.string.call_rating_level_okay),
        ResUtils.getString(R.string.call_rating_level_good),
        ResUtils.getString(R.string.call_rating_level_excellent)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(color = DifftTheme.colors.backgroundPopup, shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 0.dp, bottomEnd = 0.dp)),
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(
            modifier = Modifier
                .wrapContentSize()
                .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.Top),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(327.dp)
                    .height(68.dp)
            ) {
                Text(
                    text = ResUtils.getString(R.string.call_rating_feedback_title),
                    style = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(510),
                        color = DifftTheme.colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                )

                Text(
                    text = ResUtils.getString(R.string.call_rating_feedback_sub),
                    style = TextStyle(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        color = DifftTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(ratingDescriptions.size) { index ->
                        val starIndex = index + 1
                        val isFilled = starIndex <= rating
                        val starRes = if (isFilled)
                            R.drawable.tabler_star_filled
                        else
                            R.drawable.tabler_star

                        Image(
                            painter = painterResource(id = starRes),
                            contentDescription = "Star $starIndex",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(28.dp)
                                .padding(1.dp)
                                .clickable {
                                    rating = if (rating == starIndex) 0 else starIndex
                                    onRatingChanged(rating)
                                }
                        )
                    }
                }

                AnimatedVisibility(visible = rating > 0) {
                    Text(
                        text = ratingDescriptions.getOrNull(rating - 1) ?: "",
                        style = TextStyle(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight(400),
                            color = DifftTheme.colors.textTertiary,
                            textAlign = TextAlign.Center,
                        )
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                modifier = Modifier
                    .border(width = 1.dp, color = DifftTheme.colors.line, shape = RoundedCornerShape(size = 8.dp))
                    .weight(1f)
                    .height(48.dp)
                    .clickable {
                        onDismiss()
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ResUtils.getString(R.string.call_rating_action_cancel),
                    style = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        color = DifftTheme.colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(color = if (rating == 0) DifftTheme.colors.backgroundDisabled else DifftTheme.colors.primary, shape = RoundedCornerShape(size = 8.dp))
                    .clickable {
                        if (rating == 0) {
                            ToastUtil.show(ResUtils.getString(R.string.call_rating_feedback_no_choice_tip))
                        } else if (rating > 2) {
                            val params = CallFeedbackRequestBody(
                                userIdentity = callInfo.userIdentity,
                                userSid = callInfo.userSid,
                                roomSid = callInfo.roomSid,
                                roomId = callInfo.roomId,
                                rating = rating,
                            )
                            onSubmit(params)
                        } else {
                            showQuestion()
                        }
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ResUtils.getString(R.string.call_rating_action_submit),
                    style = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        color = if (rating == 0) DifftTheme.colors.textDisabled else Color.White,
                        textAlign = TextAlign.Center,
                    )
                )
            }
        }
    }
}
