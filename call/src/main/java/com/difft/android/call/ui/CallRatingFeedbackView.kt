package com.difft.android.call.ui

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.R
import com.difft.android.call.data.FeedbackCallInfo


private sealed class FeedbackViewState {
    object Rating : FeedbackViewState()
    object Question : FeedbackViewState()
    object Dismissed : FeedbackViewState()
}
private data class FeedbackTab(
    val title: String,
    val key: String,
    val reasons: List<String>
)

private fun createFeedbackQuestionTabs(): List<FeedbackTab> = listOf(
    FeedbackTab(
        title = ResUtils.getString(R.string.call_rating_question_category_audio),
        key = "audio",
        reasons = listOf(
            ResUtils.getString(R.string.call_rating_question_audio_0),
            ResUtils.getString(R.string.call_rating_question_audio_1),
            ResUtils.getString(R.string.call_rating_question_audio_2),
            ResUtils.getString(R.string.call_rating_question_audio_3),
            ResUtils.getString(R.string.call_rating_question_audio_4),
            ResUtils.getString(R.string.call_rating_question_audio_5),
            ResUtils.getString(R.string.call_rating_question_audio_6),
        )
    ),
    FeedbackTab(
        title = ResUtils.getString(R.string.call_rating_question_category_video),
        key = "video",
        reasons = listOf(
            ResUtils.getString(R.string.call_rating_question_video_0),
            ResUtils.getString(R.string.call_rating_question_video_1),
            ResUtils.getString(R.string.call_rating_question_video_2),
            ResUtils.getString(R.string.call_rating_question_video_3),
            ResUtils.getString(R.string.call_rating_question_video_4),
            ResUtils.getString(R.string.call_rating_question_video_5),
        )
    ),
    FeedbackTab(
        title = ResUtils.getString(R.string.call_rating_question_category_other),
        key = "other",
        reasons = listOf(
            ResUtils.getString(R.string.call_rating_question_other_0),
            ResUtils.getString(R.string.call_rating_question_other_1),
            ResUtils.getString(R.string.call_rating_question_other_2),
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallRatingFeedbackView(callInfo: FeedbackCallInfo, onDisplay: () -> Unit, onDismiss: () -> Unit, onSubmit: (CallFeedbackRequestBody) -> Unit) {

    var rating by remember { mutableIntStateOf(0) }
    var viewState by remember { mutableStateOf<FeedbackViewState>(FeedbackViewState.Rating) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    if(viewState != FeedbackViewState.Dismissed) {
        ModalBottomSheet (
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            sheetState = sheetState,
            dragHandle = null,
            containerColor = colorResource(id = com.difft.android.base.R.color.bg_popup),
            onDismissRequest = {
                viewState = FeedbackViewState.Dismissed
                onDismiss()
            },
        ){
            when (viewState) {
                is FeedbackViewState.Rating -> {
                    onDisplay()
                    CallRatingView(
                        callInfo = callInfo,
                        onRatingChanged = { rating = it },
                        onDismiss = {
                            viewState = FeedbackViewState.Dismissed
                            onDismiss()
                        },
                        onSubmit = { data ->
                            onSubmit(data)
                            onDismiss()
                        },
                        showQuestion = { viewState = FeedbackViewState.Question }
                    )
                }
                is FeedbackViewState.Question -> CallQuestionView(
                    callInfo = callInfo,
                    rating = rating,
                    onDismiss = {
                        viewState = FeedbackViewState.Dismissed
                        onDismiss()
                    },
                    onSubmit = { data ->
                        onSubmit(data)
                        onDismiss()
                    }
                )
                FeedbackViewState.Dismissed -> Unit
            }
        }
    }
}

@Composable
fun CallQuestionView(callInfo: FeedbackCallInfo, rating: Int, onDismiss: () -> Unit, onSubmit: (CallFeedbackRequestBody) -> Unit) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val tabs by remember {
        mutableStateOf(createFeedbackQuestionTabs())
    }

    val selectedReasons = remember { mutableStateListOf<String>() }
    val currentTab = tabs[selectedTabIndex]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(color = colorResource(id = com.difft.android.base.R.color.bg_popup), shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 0.dp, bottomEnd = 0.dp)),
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Top),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(327.dp)
                    .height(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = ResUtils.getString(R.string.call_rating_question_title),
                    style = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(510),
                        color = colorResource(id = com.difft.android.base.R.color.t_primary),
                        textAlign = TextAlign.Center,
                    )
                )
            }
        }

        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[selectedTabIndex])
                        .height(2.dp)
                        .padding(horizontal = 30.dp),
                    color = colorResource(id = com.difft.android.base.R.color.t_primary)
                )
            }
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = tab.title,
                            style = TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight(400),
                                color = if (selectedTabIndex == index)
                                    colorResource(id = com.difft.android.base.R.color.t_primary)
                                else
                                    colorResource(id = com.difft.android.base.R.color.t_third)
                            )
                        )
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(324.dp)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Top),
            horizontalAlignment = Alignment.Start,
        ) {
            currentTab.reasons.forEach { reason ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical = 8.dp
                        )
                        .toggleable(
                            value = selectedReasons.contains(reason),
                            onValueChange = { isSelected ->
                                if (isSelected) selectedReasons.add(reason)
                                else selectedReasons.remove(reason)
                            }
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedReasons.contains(reason),
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = colorResource(id = com.difft.android.base.R.color.primary),
                            uncheckedColor = colorResource(id = com.difft.android.base.R.color.t_disable)
                        )
                    )
                    Text(text = reason, color = colorResource(id = com.difft.android.base.R.color.t_primary), fontSize = 15.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            Row(
                modifier = Modifier
                    .border(width = 1.dp, color = colorResource(id = com.difft.android.base.R.color.line), shape = RoundedCornerShape(size = 8.dp))
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
                        color = colorResource(id = com.difft.android.base.R.color.t_primary),
                        textAlign = TextAlign.Center,
                    )
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(color = if(selectedReasons.isEmpty()) colorResource(id = com.difft.android.base.R.color.bg_disable) else colorResource(id = com.difft.android.base.R.color.primary), shape = RoundedCornerShape(size = 8.dp))
                    .clickable {
                        val reasonIndexMap = tabs.flatMap { tab ->
                            tab.reasons.mapIndexed { index, reason ->
                                reason to (tab.key to index)
                            }
                        }.toMap()

                        val reasons = selectedReasons
                            .mapNotNull { reasonIndexMap[it] }
                            .groupBy({ it.first }, { it.second })
                            .mapValues { it.value.sorted() }

                        val params = CallFeedbackRequestBody(
                            userIdentity = callInfo.userIdentity,
                            userSid = callInfo.userSid,
                            roomSid = callInfo.roomSid,
                            roomId = callInfo.roomId,
                            rating = rating,
                            reasons = reasons
                        )
                        onSubmit(params)
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
                        color = if(selectedReasons.isEmpty()) colorResource(id = com.difft.android.base.R.color.t_disable) else colorResource(id = com.difft.android.base.R.color.t_white),
                        textAlign = TextAlign.Center,
                    )
                )
            }
        }
    }
}

@Composable
fun CallRatingView(callInfo: FeedbackCallInfo, onRatingChanged: (Int) -> Unit, onDismiss: () -> Unit, onSubmit: (CallFeedbackRequestBody) -> Unit, showQuestion: () -> Unit) {
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
            .background(color = colorResource(id = com.difft.android.base.R.color.bg_popup), shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 0.dp, bottomEnd = 0.dp)),
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
                        color = colorResource(id = com.difft.android.base.R.color.t_primary),
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
                        color = colorResource(id = com.difft.android.base.R.color.t_secondary),
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
                            color = colorResource(id = com.difft.android.base.R.color.t_third),
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
                    .border(width = 1.dp, color = colorResource(id = com.difft.android.base.R.color.line), shape = RoundedCornerShape(size = 8.dp))
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
                        color = colorResource(id = com.difft.android.base.R.color.t_primary),
                        textAlign = TextAlign.Center,
                    )
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(color = if(rating == 0) colorResource(id = com.difft.android.base.R.color.bg_disable) else colorResource(id = com.difft.android.base.R.color.primary), shape = RoundedCornerShape(size = 8.dp))
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
                        color = if(rating == 0) colorResource(id = com.difft.android.base.R.color.t_disable) else colorResource(id = com.difft.android.base.R.color.t_white),
                        textAlign = TextAlign.Center,
                    )
                )
            }
        }
    }
}