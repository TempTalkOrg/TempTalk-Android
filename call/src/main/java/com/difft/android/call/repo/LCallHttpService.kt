package com.difft.android.call.repo

import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.call.CallListResponseData
import com.difft.android.base.call.ControlMessageRequestBody
import com.difft.android.base.call.ControlMessageResponseData
import com.difft.android.base.call.InviteCallRequestBody
import com.difft.android.base.call.InviteCallResponseData
import com.difft.android.base.call.ServiceUrlData
import com.difft.android.base.call.StartCallRequestBody
import com.difft.android.base.call.StartCallResponseData
import com.difft.android.call.response.RoomState
import com.difft.android.network.BaseResponse
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface LCallHttpService {

    @POST("v3/call/start")
    fun startCall(
        @Header("Authorization") authorization: String,
        @Body request: StartCallRequestBody
    ): Single<BaseResponse<StartCallResponseData>>

    @POST("v3/call/controlmessages")
    fun controlMessages(
        @Header("Authorization") authorization: String,
        @Body request: ControlMessageRequestBody
    ): Single<BaseResponse<ControlMessageResponseData>>

    @GET("v3/call")
    fun getCallingList(
        @Header("Authorization") authorization: String,
    ): Observable<BaseResponse<CallListResponseData>>

    @GET("v3/call/check")
    fun checkCall(
        @Header("Authorization") authorization: String,
        @Query("roomId") roomId: String?
    ): Observable<BaseResponse<RoomState>>

    @POST("v3/call/invite")
    fun inviteCall(
        @Header("Authorization") authorization: String,
        @Body request: InviteCallRequestBody
    ): Single<BaseResponse<InviteCallResponseData>>

    @GET("v3/call/serviceurl")
    fun getServiceUrl(
        @Header("Authorization") authorization: String,
    ): Single<BaseResponse<ServiceUrlData>>

    @POST("v3/call/feedback")
    fun callFeedback(
        @Header("Authorization") authorization: String,
        @Body request: CallFeedbackRequestBody
    ): Single<BaseResponse<Unit>>
}