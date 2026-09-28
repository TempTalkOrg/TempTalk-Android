package com.difft.android.call.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
                    style = DifftTheme.typography.h5,
                    color = DifftTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
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
                            style = DifftTheme.typography.p2,
                            color = if (selectedTabIndex == index)
                                DifftTheme.colors.textPrimary
                            else
                                DifftTheme.colors.textTertiary,
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
                DifftCheckBox(
                    checked = selectedReasons.contains(reason),
                    onCheckedChange = { isSelected ->
                        if (isSelected) selectedReasons.add(reason)
                        else selectedReasons.remove(reason)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    label = reason,
                )
            }
        }

        CallFeedbackActions(
            onCancel = onDismiss,
            submitEnabled = selectedReasons.isNotEmpty(),
            onSubmit = {
                val reasonIndexMap = tabs.flatMap { tab ->
                    tab.reasons.mapIndexed { index, reason ->
                        reason to (tab.key to index)
                    }
                }.toMap()

                val reasons = selectedReasons
                    .mapNotNull { reasonIndexMap[it] }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.sorted() }

                onSubmit(callInfo.toRequestBody(rating, reasons))
            },
        )
    }
}
