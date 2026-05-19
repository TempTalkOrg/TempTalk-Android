package com.difft.android.websocket.internal.websocket

import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.push.exceptions.CaptchaRequiredException
import com.difft.android.websocket.api.push.exceptions.DeprecatedVersionException
import com.difft.android.websocket.api.push.exceptions.ExpectationFailedException
import com.difft.android.websocket.api.push.exceptions.MalformedResponseException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.NotFoundException
import com.difft.android.websocket.api.push.exceptions.ProofRequiredException
import com.difft.android.websocket.api.push.exceptions.RateLimitException
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException
import com.difft.android.websocket.internal.push.DeviceLimit
import com.difft.android.websocket.internal.push.DeviceLimitExceededException
import com.difft.android.websocket.internal.push.LockedException
import com.difft.android.websocket.internal.push.MismatchedDevices
import com.difft.android.websocket.internal.push.ProofRequiredResponse
import com.difft.android.websocket.internal.push.RegistrationLockFailure
import com.difft.android.websocket.internal.push.SocketResponse
import com.difft.android.websocket.internal.push.StaleDevices
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import com.difft.android.websocket.internal.push.exceptions.MismatchedDevicesException
import com.difft.android.websocket.internal.push.exceptions.StaleDevicesException
import com.difft.android.websocket.internal.util.JsonUtil
import com.difft.android.websocket.internal.util.Util
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.function.Function

/**
 * A default implementation of an [ErrorMapper] that can parse most known application
 * errors.
 *
 * Can be extended to add custom error mapping via [extend].
 *
 * While this class can be used directly, it is primarily intended to be used as part of
 * [DefaultResponseMapper].
 */
class DefaultErrorMapper private constructor(
    private val customErrorMappers: Map<Int, ErrorMapper>
) : ErrorMapper {

    private constructor() : this(emptyMap())

    fun parseError(websocketResponse: WebsocketResponse): Throwable? {
        return parseError(
            websocketResponse.status,
            websocketResponse.body
        ) { key -> websocketResponse.getHeader(key) ?: "" }
    }

    override fun parseError(
        status: Int,
        body: String,
        getHeader: Function<String, String>
    ): Throwable? {
        // Check custom mappers first — callers like NewMessageService register overrides
        // for 404/409/410 to return group-specific exception types.
        customErrorMappers[status]?.let { customMapper ->
            return try {
                customMapper.parseError(status, body, getHeader)
            } catch (e: MalformedResponseException) {
                e
            }
        }

        return when (status) {
            401, 403 -> AuthorizationFailedException(status, "Authorization failed!")

            402 -> CaptchaRequiredException()

            404 -> {
                val socketResponse: SocketResponse? = try {
                    JsonUtil.fromJsonResponse(body, SocketResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                if (socketResponse != null && socketResponse.status in setOf(10105, 10110)) {
                    AccountOfflineException(socketResponse.status, socketResponse.reason)
                } else {
                    NotFoundException("At least one unregistered user in message send.")
                }
            }

            409 -> try {
                MismatchedDevicesException(JsonUtil.fromJsonResponse(body, MismatchedDevices::class.java))
            } catch (e: MalformedResponseException) {
                e
            }

            410 -> try {
                StaleDevicesException(JsonUtil.fromJsonResponse(body, StaleDevices::class.java))
            } catch (e: MalformedResponseException) {
                e
            }

            411 -> try {
                DeviceLimitExceededException(JsonUtil.fromJsonResponse(body, DeviceLimit::class.java))
            } catch (e: MalformedResponseException) {
                e
            }

            413, 429 -> {
                val retryAfterLong = Util.parseLong(getHeader.apply("Retry-After"), -1)
                val retryAfter = if (retryAfterLong != -1L) {
                    Optional.of(TimeUnit.SECONDS.toMillis(retryAfterLong))
                } else {
                    Optional.empty()
                }
                RateLimitException(status, "Rate limit exceeded: $status", retryAfter)
            }

            417 -> ExpectationFailedException()

            423 -> {
                val accountLockFailure: RegistrationLockFailure = try {
                    JsonUtil.fromJsonResponse(body, RegistrationLockFailure::class.java)
                } catch (e: MalformedResponseException) {
                    return e
                }

                LockedException(
                    accountLockFailure.length,
                    accountLockFailure.timeRemaining,
                    accountLockFailure.backupCredentials?.asBasic()
                )
            }

            428 -> {
                val proofRequiredResponse: ProofRequiredResponse = try {
                    JsonUtil.fromJsonResponse(body, ProofRequiredResponse::class.java)
                } catch (e: MalformedResponseException) {
                    return e
                }
                val retryAfterRaw = getHeader.apply("Retry-After")
                val retryAfter = Util.parseInt(retryAfterRaw, -1).toLong()

                ProofRequiredException(proofRequiredResponse, retryAfter)
            }

            499 -> DeprecatedVersionException()

            508 -> ServerRejectedException()

            else -> if (status !in setOf(200, 202, 204)) {
                NonSuccessfulResponseCodeException(status, "Bad response: $status")
            } else {
                null
            }
        }
    }

    class Builder {
        private val customErrorMappers = mutableMapOf<Int, ErrorMapper>()

        fun withCustom(status: Int, errorMapper: ErrorMapper): Builder {
            customErrorMappers[status] = errorMapper
            return this
        }

        fun build(): ErrorMapper {
            return DefaultErrorMapper(customErrorMappers)
        }
    }

    companion object {
        private val INSTANCE = DefaultErrorMapper()

        @JvmStatic
        fun getDefault(): DefaultErrorMapper = INSTANCE

        @JvmStatic
        fun extend(): Builder = Builder()
    }
}
