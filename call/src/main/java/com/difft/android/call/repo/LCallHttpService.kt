package com.difft.android.call.repo

import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.call.CallListResponseData
import com.difft.android.base.call.ControlMessageRequestBody
import com.difft.android.base.call.ControlMessageResponseData
import com.difft.android.base.call.InviteCallRequestBody
import com.difft.android.base.call.InviteCallResponseData
import com.difft.android.base.call.ServiceUrlDataV2
import com.difft.android.base.call.StatisticsLogRequestBody
import com.difft.android.call.response.RoomState
import com.difft.android.network.BaseResponse
import com.difft.android.network.auth.Auth
import com.difft.android.network.auth.AuthStyle
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

@Auth(AuthStyle.JWT)
interface LCallHttpService {

    @POST("v3/call/controlmessages")
    suspend fun controlMessages(
        @Body request: ControlMessageRequestBody
    ): BaseResponse<ControlMessageResponseData>

    @GET("v3/call")
    suspend fun getCallingList(): BaseResponse<CallListResponseData>

    @GET("v3/call/check")
    suspend fun checkCall(
        @Query("roomId") roomId: String?
    ): BaseResponse<RoomState>

    @POST("v3/call/invite")
    suspend fun inviteCall(
        @Body request: InviteCallRequestBody
    ): BaseResponse<InviteCallResponseData>

    @GET("v3/call/serviceurl/v2")
    suspend fun getServiceUrlV2(): BaseResponse<ServiceUrlDataV2>

    @POST("v3/call/feedback")
    suspend fun callFeedback(
        @Body request: CallFeedbackRequestBody
    ): BaseResponse<Unit>

    @POST("v3/call/statistics/log")
    suspend fun statisticsLog(
        @Body request: StatisticsLogRequestBody
    ): BaseResponse<Unit>
}