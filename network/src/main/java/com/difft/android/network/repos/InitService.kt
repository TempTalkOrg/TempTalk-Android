package com.difft.android.network.repos

import com.difft.android.network.BaseResponse
import com.difft.android.network.requests.GetHotMsgParams
import com.difft.android.network.requests.GetReadPositionParams
import com.difft.android.network.responses.ConversationMsgInfoPojo
import com.difft.android.network.responses.HotMsgPojo
import com.difft.android.network.responses.ReadPositionResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST

interface InitService {
    @GET("/v1/messages/getConversationMsg")
    suspend fun getConversationMsg(): BaseResponse<ConversationMsgInfoPojo>

    @POST("v1/messages/getHotMsg")
    @Headers("Content-Type:application/json")
    suspend fun getHotMsg(
        @Body body: GetHotMsgParams
    ): BaseResponse<HotMsgPojo>

    @POST("v1/readReceipt/getReadPosition")
    @Headers("Content-Type:application/json")
    suspend fun getReadPosition(
        @Body body: GetReadPositionParams
    ): BaseResponse<ReadPositionResponse>

}