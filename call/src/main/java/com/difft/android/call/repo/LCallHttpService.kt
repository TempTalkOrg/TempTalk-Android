package com.difft.android.call.repo

import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.call.CallListResponseData
import com.difft.android.base.call.ControlMessageRequestBody
import com.difft.android.base.call.ControlMessageResponseData
import com.difft.android.base.call.InviteCallRequestBody
import com.difft.android.base.call.InviteCallResponseData
import com.difft.android.base.call.ServiceUrlData
import com.difft.android.call.response.RoomState
import com.difft.android.network.BaseResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface LCallHttpService {

    @POST("v3/call/controlmessages")
    suspend fun controlMessages(
        @Header("Authorization") authorization: String,
        @Body request: ControlMessageRequestBody
    ): BaseResponse<ControlMessageResponseData>

    @GET("v3/call")
    suspend fun getCallingList(
        @Header("Authorization") authorization: String,
    ): BaseResponse<CallListResponseData>

    @GET("v3/call/check")
    suspend fun checkCall(
        @Header("Authorization") authorization: String,
        @Query("roomId") roomId: String?
    ): BaseResponse<RoomState>

    @POST("v3/call/invite")
    suspend fun inviteCall(
        @Header("Authorization") authorization: String,
        @Body request: InviteCallRequestBody
    ): BaseResponse<InviteCallResponseData>

    @GET("v3/call/serviceurl")
    suspend fun getServiceUrl(
        @Header("Authorization") authorization: String,
    ): BaseResponse<ServiceUrlData>

    @POST("v3/call/feedback")
    suspend fun callFeedback(
        @Header("Authorization") authorization: String,
        @Body request: CallFeedbackRequestBody
    ): BaseResponse<Unit>
}