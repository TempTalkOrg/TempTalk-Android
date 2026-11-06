package com.difft.android.chat.focus

import com.difft.android.network.BaseResponse
import com.difft.android.network.auth.Auth
import com.difft.android.network.auth.AuthStyle
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * Group focus endpoints. Business errors come back as HTTP 200 with a non-zero body `status`
 * (see [FocusError]); every success carries the full snapshot.
 */
@Auth(AuthStyle.BASIC)
interface FocusApi {
    @GET("v1/focus")
    suspend fun getFocus(): BaseResponse<FocusResponse>

    @PUT("v1/focus")
    suspend fun openFocus(@Body body: FocusOpenRequest): BaseResponse<FocusResponse>

    @PUT("v1/focus/until")
    suspend fun changeUntil(@Body body: FocusUntilRequest): BaseResponse<FocusResponse>

    @PUT("v1/focus/options")
    suspend fun updateOptions(@Body body: FocusOptionsRequest): BaseResponse<FocusResponse>

    @DELETE("v1/focus")
    suspend fun endFocus(): BaseResponse<FocusResponse>
}

/** Body `status` of a failed focus call, plus transport failure. */
sealed class FocusError {
    /** 1: bad body, illegal duration, empty or oversized gids, empty options. */
    data object InvalidParameter : FocusError()

    /** 2: the account is not a member of the group. */
    data object NoPermission : FocusError()

    /** 3: the group does not exist or is no longer active. */
    data object NoSuchGroup : FocusError()

    /** 31: the focus state does not allow this write; the caller should refresh. */
    data object Conflict : FocusError()

    /** 32: the server feature switch is off. */
    data object FeatureDisabled : FocusError()

    /** Any other non-zero status. */
    data class Unknown(val status: Int) : FocusError()

    /** The request did not complete (offline, timeout, HTTP 5xx, bad body). */
    data class Network(val message: String?) : FocusError()

    companion object {
        const val STATUS_INVALID_PARAMETER = 1
        const val STATUS_NO_PERMISSION = 2
        const val STATUS_NO_SUCH_GROUP = 3
        const val STATUS_CONFLICT = 31
        const val STATUS_FEATURE_DISABLED = 32

        fun fromStatus(status: Int): FocusError = when (status) {
            STATUS_INVALID_PARAMETER -> InvalidParameter
            STATUS_NO_PERMISSION -> NoPermission
            STATUS_NO_SUCH_GROUP -> NoSuchGroup
            STATUS_CONFLICT -> Conflict
            STATUS_FEATURE_DISABLED -> FeatureDisabled
            else -> Unknown(status)
        }
    }
}

/** Outcome of a focus write. On [Success] the repository already applied the returned snapshot. */
sealed class FocusResult {
    data class Success(val focus: FocusSnapshot) : FocusResult()
    data class Failure(val error: FocusError) : FocusResult()
}
