package com.difft.android.call.ui.feedback

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.ui.compose.DifftCheckBox
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.data.FeedbackCallInfo

@Composable
internal fun CallQuestionView(
    callInfo: FeedbackCallInfo,
    rating: Int,
    onDismiss: () -> Unit,
    onSubmit: (CallFeedbackRequestBody) -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val tabs by remember {
        mutableStateOf(createFeedbackQuestionTabs())
    }

    val selectedReasons = remember { mutableStateListOf<String>() }
    val currentTab = tabs[selectedTabIndex]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
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
                        color = DifftTheme.colors.textPrimary,
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
                    color = DifftTheme.colors.textPrimary
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
                                    DifftTheme.colors.textPrimary
                                else
                                    DifftTheme.colors.textTertiary
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
                    DifftCheckBox(
                        checked = selectedReasons.contains(reason),
                        onCheckedChange = null,
                        // 24dp inline footprint keeps the glyph aligned with the 15sp label's line box.
                        modifier = Modifier.size(24.dp),
                    )
                    Text(text = reason, color = DifftTheme.colors.textPrimary, fontSize = 15.sp)
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
                    .background(color = if (selectedReasons.isEmpty()) DifftTheme.colors.backgroundDisabled else DifftTheme.colors.primary, shape = RoundedCornerShape(size = 8.dp))
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
                        color = if (selectedReasons.isEmpty()) DifftTheme.colors.textDisabled else Color.White,
                        textAlign = TextAlign.Center,
                    )
                )
            }
        }
    }
}
