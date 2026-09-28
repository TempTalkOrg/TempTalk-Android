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

/**
 * Bottom action bar of the post-call feedback sheets (Figma `Two_Button`, Yelling-Metting
 * `10860:7860` / `10860:7581`): an outlined Not Now and a filled Submit of equal width, 16dp apart,
 * on a 16dp inset. The row takes its height from the buttons (80dp at the default font scale) so a
 * larger font scale grows the labels instead of clipping them. Submit is disabled until the sheet
 * has something to send, so a premature tap does nothing — no toast.
 */
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

/** The feedback request for this call: the ids come from the call, the score and reasons from the user. */
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
