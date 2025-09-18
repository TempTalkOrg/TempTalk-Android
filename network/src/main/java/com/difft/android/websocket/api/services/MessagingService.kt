package com.difft.android.websocket.api.services

import com.difft.android.base.log.lumberjack.L.d
import com.google.protobuf.ByteString
import io.reactivex.rxjava3.core.Single
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.push.exceptions.NotFoundException
import com.difft.android.websocket.internal.ServiceResponse
import com.difft.android.websocket.internal.ServiceResponseProcessor
import com.difft.android.websocket.internal.push.GroupMismatchedDevices
import com.difft.android.websocket.internal.push.GroupStaleDevices
import com.difft.android.websocket.internal.push.MismatchedDevices
import com.difft.android.websocket.internal.push.OutgoingPushMessageList
import com.difft.android.websocket.internal.push.SendMessageResponse
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
import java.util.LinkedList
import java.util.Objects
import java.util.function.Function
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provide WebSocket based interface to message sending endpoints.
 *
 *
 * Note: To be expanded to have REST fallback and other messaging related operations.
 */
@Singleton
class MessagingService @Inject constructor(private val appWebSocketHelper: AppWebSocketHelper) {
    fun send(
        list: OutgoingPushMessageList,
        recipientId: String?
    ): Single<ServiceResponse<SendMessageResponse>> {
        val basePath = "/v1/messages/destination/%s"
        return innerSend(list, String.format(basePath, recipientId), false)
    }

    fun sendToGroup(
        list: OutgoingPushMessageList,
        groupId: String
    ): Single<ServiceResponse<SendMessageResponse>> {
        return innerSend(list, String.format("/v1/messages/group/%s", groupId), true)
    }

    private fun innerSend(
        list: OutgoingPushMessageList,
        path: String,
        isGroup: Boolean
    ): Single<ServiceResponse<SendMessageResponse>> {
        Objects.requireNonNull(path)
        d { "===send message json body===" + JsonUtil.toJson(list) }
        val headers: List<String> = object : LinkedList<String>() {
            init {
                add("content-type:application/json")
            }
        }
        val requestMessage = WebSocketRequestMessage.newBuilder()
            .setRequestId(SecureRandom().nextLong())
            .setVerb("PUT")
            .setPath(path)
            .addAllHeaders(headers)
            .setBody(ByteString.copyFrom(JsonUtil.toJson(list).toByteArray()))
            .build()

        val responseMapper = DefaultResponseMapper.extend(
            SendMessageResponse::class.java
        )
            .withResponseMapper { status: Int, body: String?, getHeader: Function<String?, String?>? ->
                val SendMessageResponse = if (Util.isEmpty(body)) SendMessageResponse(
                    false,
                    0,
                    System.currentTimeMillis(),
                    0
                )
                else JsonUtil.fromJsonResponse(body, SendMessageResponse::class.java)
                ServiceResponse.forResult(SendMessageResponse, status, body)
            }
            .withCustomError(404) { status: Int, body: String?, getHeader: Function<String?, String?>? ->
                NotFoundException(
                    "not found"
                )
            }
            .withCustomError(409) { status: Int, errorBody: String?, getHeader: Function<String?, String?>? ->
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
            .withCustomError(410) { status: Int, errorBody: String?, getHeader: Function<String?, String?>? ->
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

        return appWebSocketHelper.sendChatMessage(requestMessage)
            .map { response: WebsocketResponse? -> responseMapper.map(response) }
            .onErrorReturn { throwable: Throwable? -> ServiceResponse.forUnknownError(throwable) }
    }

    class SendResponseProcessor<T>(response: ServiceResponse<T>?) :
        ServiceResponseProcessor<T>(response)
}
