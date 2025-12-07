package com.difft.android.call.data


/**
 * Contains feedback call information for tracking purposes.
 * @param userIdentity User's display identifier
 * @param userSid User's system identifier
 * @param roomId Room's display identifier
 * @param roomSid Room's system identifier
 */
data class FeedbackCallInfo(
    val userIdentity: String,
    val userSid: String,
    val roomId: String,
    val roomSid: String
)