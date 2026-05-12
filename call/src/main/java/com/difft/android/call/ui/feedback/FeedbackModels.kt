package com.difft.android.call.ui.feedback

import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R

internal sealed class FeedbackViewState {
    object Rating : FeedbackViewState()
    object Question : FeedbackViewState()
    object Dismissed : FeedbackViewState()
}

internal data class FeedbackTab(
    val title: String,
    val key: String,
    val reasons: List<String>
)

internal fun createFeedbackQuestionTabs(): List<FeedbackTab> = listOf(
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
