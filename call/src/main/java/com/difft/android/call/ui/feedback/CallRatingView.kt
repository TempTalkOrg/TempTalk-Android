package com.difft.android.call.ui.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
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
            .wrapContentHeight(),
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
                    style = DifftTheme.typography.h5,
                    color = DifftTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = ResUtils.getString(R.string.call_rating_feedback_sub),
                    style = DifftTheme.typography.footnote,
                    color = DifftTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
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
                        style = DifftTheme.typography.p4,
                        color = DifftTheme.colors.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // A low score (1-2 stars) asks what went wrong before submitting.
        CallFeedbackActions(
            onCancel = onDismiss,
            submitEnabled = rating > 0,
            onSubmit = {
                if (rating > 2) onSubmit(callInfo.toRequestBody(rating)) else showQuestion()
            },
        )
    }
}
