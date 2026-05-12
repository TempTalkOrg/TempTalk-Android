package com.difft.android.call.ui.feedback

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.call.data.FeedbackCallInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallRatingFeedbackView(
    callInfo: FeedbackCallInfo,
    onDisplay: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (CallFeedbackRequestBody) -> Unit
) {
    var rating by remember { mutableIntStateOf(0) }
    var viewState by remember { mutableStateOf<FeedbackViewState>(FeedbackViewState.Rating) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    if (viewState != FeedbackViewState.Dismissed) {
        ModalBottomSheet(
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
        ) {
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
