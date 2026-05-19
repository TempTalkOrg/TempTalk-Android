package com.difft.android.network.signal

import com.difft.android.base.log.lumberjack.L
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.push.exceptions.MalformedResponseException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.NotFoundException
import com.difft.android.websocket.api.push.exceptions.PushNetworkException
import com.difft.android.websocket.api.push.exceptions.RateLimitException
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException
import com.difft.android.websocket.api.push.exceptions.UnregisteredUserException
import com.difft.android.websocket.internal.push.MismatchedDevices
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import com.difft.android.websocket.internal.push.NewSendMessageResponse
import com.difft.android.websocket.internal.push.SocketResponse
import com.difft.android.websocket.internal.push.StaleDevices
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import com.difft.android.websocket.internal.push.exceptions.MismatchedDevicesException
import com.difft.android.websocket.internal.push.exceptions.StaleDevicesException
import com.difft.android.websocket.internal.util.JsonUtil
import com.difft.android.websocket.internal.util.Util
import difft.android.messageserialization.For
import okhttp3.ResponseBody
import retrofit2.Response
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import java.io.IOException
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageSendRepository @Inject constructor(
    @param:ChativeHttpClientModule.SignalApi
    private val httpClient: ChativeHttpClient
) {
    private val messageApiService: MessageApiService =
        httpClient.getService(MessageApiService::class.java)
    /**
     * Send a message via HTTP fallback.
     * Replaces PushServiceSocket.sendMessageNew().
     * Error semantics are identical to PushServiceSocket.validateResponse().
     */
    suspend fun sendMessage(
        message: NewOutgoingPushMessage,
        recipient: For
    ): NewSendMessageResponse {
        L.d { "[MessageSendRepository] sendMessage: recipient=${recipient.id}, isGroup=${recipient is For.Group}" }
        val response = try {
            if (recipient is For.Group) {
                messageApiService.sendGroupMessage(recipient.id, message)
            } else {
                messageApiService.sendMessage(recipient.id, message)
            }
        } catch (e: IOException) {
            L.w(e) { "[MessageSendRepository] sendMessage: network error for ${recipient.id}" }
            throw PushNetworkException(e)
        }

        val code = response.code()
        if (code == 200 || code == 202) {
            L.d { "[MessageSendRepository] sendMessage: success ($code)" }
            return parseSuccessResponse(response)
        } else if (code == 204) {
            L.d { "[MessageSendRepository] sendMessage: success (204 No Content)" }
            return NewSendMessageResponse()
        }

        L.w { "[MessageSendRepository] sendMessage: error $code for ${recipient.id}" }
        val bodyString = readErrorBody(response)
        throwForErrorCode(code, bodyString, response, recipient.id)
    }

    private fun parseSuccessResponse(
        response: Response<ResponseBody>
    ): NewSendMessageResponse {
        val bodyString = response.body()?.string()
            ?: throw MalformedResponseException("200 response with empty body")

        return try {
            JsonUtil.fromJson(bodyString, NewSendMessageResponse::class.java)
        } catch (e: IOException) {
            throw MalformedResponseException("Failed to parse message send response", e)
        } catch (e: Exception) {
            throw MalformedResponseException("Failed to parse message send response")
        }
    }

    private fun readErrorBody(response: Response<ResponseBody>): String? =
        try { response.errorBody()?.string() } catch (_: Exception) { null }

    /**
     * Maps HTTP error codes to domain exceptions.
     * Directly mirrors PushServiceSocket.validateResponse().
     */
    private fun throwForErrorCode(
        code: Int,
        bodyString: String?,
        response: Response<*>,
        recipientId: String
    ): Nothing {
        L.d { "[MessageSendRepository] throwForErrorCode: code=$code, body=$bodyString" }
        when (code) {
            401, 403 -> throw AuthorizationFailedException(code, "Authorization failed!")

            404 -> {
                if (bodyString != null) {
                    try {
                        val socketResponse = JsonUtil.fromJson(bodyString, SocketResponse::class.java)
                        if (socketResponse != null && socketResponse.status in setOf(10105, 10110)) {
                            L.w { "[MessageSendRepository] throwForErrorCode: account offline, status=${socketResponse.status}" }
                            throw AccountOfflineException(socketResponse.status, socketResponse.reason)
                        }
                    } catch (e: AccountOfflineException) {
                        throw e
                    } catch (_: Exception) {
                        // Ignore parsing errors, fall through to UnregisteredUserException
                    }
                }
                L.w { "[MessageSendRepository] throwForErrorCode: unregistered user $recipientId" }
                throw UnregisteredUserException(recipientId, NotFoundException("Not found"))
            }

            409 -> {
                if (bodyString != null) {
                    try {
                        throw MismatchedDevicesException(JsonUtil.fromJson(bodyString, MismatchedDevices::class.java))
                    } catch (e: MismatchedDevicesException) {
                        L.w { "[MessageSendRepository] throwForErrorCode: mismatched devices for $recipientId" }
                        throw e
                    } catch (_: Exception) {
                        // Ignore parsing errors, fall through
                    }
                }
                throw NonSuccessfulResponseCodeException(409, "Mismatched devices")
            }

            410 -> {
                if (bodyString != null) {
                    try {
                        throw StaleDevicesException(JsonUtil.fromJson(bodyString, StaleDevices::class.java))
                    } catch (e: StaleDevicesException) {
                        L.w { "[MessageSendRepository] throwForErrorCode: stale devices for $recipientId" }
                        throw e
                    } catch (_: Exception) {
                        // Ignore parsing errors, fall through
                    }
                }
                throw NonSuccessfulResponseCodeException(410, "Stale devices")
            }

            413, 429 -> {
                val retryAfterLong = Util.parseLong(response.headers()["Retry-After"], -1)
                val retryAfter = if (retryAfterLong != -1L) {
                    Optional.of(TimeUnit.SECONDS.toMillis(retryAfterLong))
                } else {
                    Optional.empty()
                }
                L.w { "[MessageSendRepository] throwForErrorCode: rate limited ($code), retryAfter=$retryAfterLong" }
                throw RateLimitException(code, "Rate limit exceeded: $code", retryAfter)
            }

            508 -> throw ServerRejectedException()
        }

        throw NonSuccessfulResponseCodeException(code, "Bad response: $code")
    }
}
