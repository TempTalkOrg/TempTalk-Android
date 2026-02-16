package com.difft.android.network

/**
 * Exception for tracking special response scenarios in Firebase Crashlytics.
 * This is separate from NetworkException to allow Firebase to group them differently.
 *
 * Used for tracking non-standard API responses that should be fixed by backend:
 * - 204 No Content (should return standard BaseResponse)
 * - Non-2xx with reason field (should return 200 directly)
 * - 2xx responses not following BaseResponse convention
 *
 * These are NOT real errors, but tracking data for backend improvement.
 */
class SpecialResponseTrackingException(
    val type: String,
    message: String
) : Exception("[$type] $message") {

    companion object {
        /** Server returned 204 No Content, should return standard BaseResponse instead */
        const val RESPONSE_204_NO_CONTENT = "RESPONSE_204_NO_CONTENT"

        /** Server returned non-2xx with reason field, should return 200 directly */
        const val RESPONSE_NON_2XX_WITH_REASON = "RESPONSE_NON_2XX_WITH_REASON"
    }
}