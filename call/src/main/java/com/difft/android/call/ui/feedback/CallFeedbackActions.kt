package com.difft.android.call.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.compose.DifftButtonVariant
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.data.FeedbackCallInfo






@Composable
internal fun CallFeedbackActions(
    onCancel: () -> Unit,
    submitEnabled: Boolean,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DifftButton(
            text = ResUtils.getString(R.string.call_rating_action_cancel),
            onClick = onCancel,
            variant = DifftButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
        DifftButton(
            text = ResUtils.getString(R.string.call_rating_action_submit),
            onClick = onSubmit,
            enabled = submitEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}


internal fun FeedbackCallInfo.toRequestBody(
    rating: Int,
    reasons: Map<String, List<Int>> = emptyMap(),
): CallFeedbackRequestBody = CallFeedbackRequestBody(
    userIdentity = userIdentity,
    userSid = userSid,
    roomSid = roomSid,
    roomId = roomId,
    rating = rating,
    reasons = reasons,
)
