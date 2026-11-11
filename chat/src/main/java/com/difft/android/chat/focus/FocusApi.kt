package com.difft.android.chat.focus

import com.difft.android.network.BaseResponse
import com.difft.android.network.auth.Auth
import com.difft.android.network.auth.AuthStyle
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT





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


sealed class FocusError {
    
    data object InvalidParameter : FocusError()

    
    data object NoPermission : FocusError()

    
    data object NoSuchGroup : FocusError()

    
    data object Conflict : FocusError()

    
    data object FeatureDisabled : FocusError()

    
    data class Unknown(val status: Int) : FocusError()

    
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


sealed class FocusResult {
    data class Success(val focus: FocusSnapshot) : FocusResult()
    data class Failure(val error: FocusError) : FocusResult()
}
