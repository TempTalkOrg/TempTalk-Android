package com.difft.android.websocket.api.services

import com.difft.android.base.log.lumberjack.L
import com.google.protobuf.ByteString
import io.reactivex.rxjava3.core.Single
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.push.exceptions.NotFoundException
import com.difft.android.websocket.internal.ServiceResponse
import com.difft.android.websocket.internal.push.GroupMismatchedDevices
import com.difft.android.websocket.internal.push.GroupStaleDevices
import com.difft.android.websocket.internal.push.MismatchedDevices
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import com.difft.android.websocket.internal.push.NewSendMessageResponse
import com.difft.android.websocket.internal.push.StaleDevices
import com.difft.android.websocket.internal.push.exceptions.GroupMismatchedDevicesException
import com.difft.android.websocket.internal.push.exceptions.GroupStaleDevicesException
import com.difft.android.websocket.internal.push.exceptions.MismatchedDevicesException
import com.difft.android.websocket.internal.push.exceptions.StaleDevicesException
import com.difft.android.websocket.internal.util.JsonUtil
import com.difft.android.websocket.internal.util.Util
import com.difft.android.websocket.internal.websocket.DefaultResponseMapper
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketRequestMessage
import com.difft.android.websocket.internal.websocket.WebsocketResponse
import java.security.SecureRandom
import java.util.Objects
import java.util.function.Function
import javax.inject.Inject

/**
 * Provide WebSocket based interface to message sending endpoints.
 *
 *
 * Note: To be expanded to have REST fallback and other messaging related operations.
 */
class NewMessagingService @Inject constructor(private val appWebSocketHelper: AppWebSocketHelper) {
    fun send(
        newOutgoingPushMessage: NewOutgoingPushMessage,
        recipientId: String?,
    ): Single<ServiceResponse<NewSendMessageResponse>> {
        val basePath = "/v3/messages/%s"
        return innerSend(newOutgoingPushMessage, String.format(basePath, recipientId), false)
    }

    fun sendToGroup(
        newOutgoingPushMessage: NewOutgoingPushMessage,
        groupId: String,
    ): Single<ServiceResponse<NewSendMessageResponse>> {
        return innerSend(
            newOutgoingPushMessage,
            String.format("/v3/messages/group/%s", groupId),
            true
        )
    }

    private fun innerSend(
        newOutgoingPushMessage: NewOutgoingPushMessage,
        path: String,
        isGroup: Boolean
    ): Single<ServiceResponse<NewSendMessageResponse>> {
        L.i { "[Message] send message with request json: ${newOutgoingPushMessage.timestamp}" }
        Objects.requireNonNull(path)
        val headers: List<String> = listOf("content-type:application/json")
        val requestMessage = WebSocketRequestMessage.newBuilder()
            .setRequestId(SecureRandom().nextLong())
            .setVerb("PUT")
            .setPath(path)
            .addAllHeaders(headers)
            .setBody(ByteString.copyFrom(JsonUtil.toJson(newOutgoingPushMessage).toByteArray()))
            .build()
        val responseMapper = DefaultResponseMapper.extend(
            NewSendMessageResponse::class.java
        )
            .withResponseMapper { status: Int, body: String?, getHeader: Function<String?, String?>? ->
                val newSendMessageResponse =
                    if (Util.isEmpty(body)) NewSendMessageResponse(
                        0, 0, "",
                        NewSendMessageResponse.Data(),
                    ) else JsonUtil.fromJsonResponse(
                        body,
                        NewSendMessageResponse::class.java
                    )
                ServiceResponse.forResult(newSendMessageResponse, status, body)
            }
            .withCustomError(
                404
            ) { status: Int, body: String?, getHeader: Function<String?, String?>? ->
                NotFoundException(
                    "not found"
                )
            }
            .withCustomError(
                409
            ) { status: Int, errorBody: String?, getHeader: Function<String?, String?>? ->
                if (isGroup) {
                    return@withCustomError GroupMismatchedDevicesException(
                        JsonUtil.fromJsonResponse<Array<GroupMismatchedDevices>>(
                            errorBody,
                            Array<GroupMismatchedDevices>::class.java
                        )
                    )
                } else {
                    return@withCustomError MismatchedDevicesException(
                        JsonUtil.fromJsonResponse<MismatchedDevices>(
                            errorBody,
                            MismatchedDevices::class.java
                        )
                    )
                }
            }
            .withCustomError(
                410
            ) { status: Int, errorBody: String?, getHeader: Function<String?, String?>? ->
                if (isGroup) {
                    return@withCustomError GroupStaleDevicesException(
                        JsonUtil.fromJsonResponse<Array<GroupStaleDevices>>(
                            errorBody,
                            Array<GroupStaleDevices>::class.java
                        )
                    )
                } else {
                    return@withCustomError StaleDevicesException(
                        JsonUtil.fromJsonResponse<StaleDevices>(
                            errorBody,
                            StaleDevices::class.java
                        )
                    )
                }
            }
            .build()
        return appWebSocketHelper.sendChatMessage(requestMessage )
            .map { response: WebsocketResponse? ->
                L.d { "[Message] send message with response json: ${response?.body}" }
                responseMapper.map(
                    response
                )
            }
            .onErrorReturn { throwable: Throwable? ->
                ServiceResponse.forUnknownError(
                    throwable
                )
            }
    }
}