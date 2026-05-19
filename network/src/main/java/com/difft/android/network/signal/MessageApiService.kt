package com.difft.android.network.signal

import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PUT
import retrofit2.http.Path

interface MessageApiService {

    /** PUT /v4/messages/{recipientId} */
    @PUT("v4/messages/{recipientId}")
    suspend fun sendMessage(
        @Path("recipientId") recipientId: String,
        @Body message: NewOutgoingPushMessage
    ): Response<ResponseBody>

    /** PUT /v4/messages/group/{groupId} */
    @PUT("v4/messages/group/{groupId}")
    suspend fun sendGroupMessage(
        @Path("groupId") groupId: String,
        @Body message: NewOutgoingPushMessage
    ): Response<ResponseBody>
}
