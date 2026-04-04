package com.difft.android.websocket.internal.push

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import com.difft.android.network.ca.OfficialSSLSocketFactoryCreator
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.push.exceptions.MalformedResponseException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.NotFoundException
import com.difft.android.websocket.api.push.exceptions.PushNetworkException
import com.difft.android.websocket.api.push.exceptions.RateLimitException
import com.difft.android.websocket.api.push.exceptions.UnregisteredUserException
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import com.difft.android.websocket.internal.push.exceptions.MismatchedDevicesException
import com.difft.android.websocket.internal.push.exceptions.StaleDevicesException
import com.difft.android.websocket.api.util.Tls12SocketFactory
import com.difft.android.websocket.internal.configuration.ServiceConfig
import com.difft.android.websocket.internal.util.JsonUtil
import com.difft.android.websocket.internal.util.Util
import com.difft.android.base.utils.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import difft.android.messageserialization.For
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class PushServiceSocket(
    config: ServiceConfig,
    private val automaticNetworkRetry: Boolean
) {
    companion object {
        private const val PROVISIONING_CODE_PATH = "/v1/devices/provisioning/code"
        private const val PROVISIONING_MESSAGE_PATH = "/v1/provisioning/%s"
        private const val DEVICE_PATH = "/v1/devices/%s"
        private val NO_HEADERS: Map<String, String> = emptyMap()
        private val NO_HANDLER: ResponseCodeHandler = EmptyResponseCodeHandler()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun createConnectionClient(config: ServiceConfig): OkHttpClient {
            val sslCreator = OfficialSSLSocketFactoryCreator(application)
            val connectionSpec = config.connectionSpec ?: ConnectionSpec.RESTRICTED_TLS

            return OkHttpClient.Builder()
                .sslSocketFactory(Tls12SocketFactory(sslCreator.socketFactory), sslCreator.trustManager)
                .connectionSpecs(Util.immutableList(connectionSpec))
                .dns(Dns.SYSTEM)
                .connectionPool(ConnectionPool(5, 45, TimeUnit.SECONDS))
                .build()
        }

        private fun readBodyString(body: ResponseBody?): String {
            if (body == null) {
                throw PushNetworkException("No body!")
            }
            return try {
                body.string()
            } catch (e: IOException) {
                throw PushNetworkException(e)
            }
        }
    }

    private var soTimeoutMillis: Long = TimeUnit.SECONDS.toMillis(15)
    private val connections: MutableSet<Call> = HashSet()
    private val client: OkHttpClient = createConnectionClient(config)
    private val serviceUrl: String = config.url
    private val headers: Map<String, String> = config.headers
    private val random: SecureRandom = SecureRandom()

    @Throws(IOException::class)
    fun getNewDeviceVerificationCode(): String {
        val responseText = makeServiceRequest(PROVISIONING_CODE_PATH, "GET", null)
        return JsonUtil.fromJson(responseText, DeviceCode::class.java).verificationCode
    }

    @Throws(IOException::class)
    fun getDevices(): List<DeviceInfo> {
        val responseText = makeServiceRequest(String.format(DEVICE_PATH, ""), "GET", null)
        return JsonUtil.fromJson(responseText, DeviceInfoList::class.java).devices
    }

    @Throws(PushNetworkException::class, NonSuccessfulResponseCodeException::class, MalformedResponseException::class)
    fun getDevicesResponse(): Response {
        return makeServiceRequest(String.format(DEVICE_PATH, ""), "GET", jsonRequestBody(null), NO_HEADERS, NO_HANDLER)
    }

    @Throws(IOException::class)
    fun sendProvisioningMessage(destination: String, body: ByteArray) {
        makeServiceRequest(
            String.format(PROVISIONING_MESSAGE_PATH, destination),
            "PUT",
            JsonUtil.toJson(ProvisioningMessage(Base64.encodeBytes(body)))
        )
    }

    @Throws(IOException::class)
    fun sendMessageNew(bundle: NewOutgoingPushMessage, recipient: For): NewSendMessageResponse {
        return try {
            val basePath = if (recipient is For.Group) {
                "/v4/messages/group/%s"  // v4 API for group messages, server handles sync
            } else {
                "/v4/messages/%s"  // v4 API for 1v1 messages with syncContent support
            }
            val responseText = makeServiceRequest(
                String.format(basePath, recipient.id),
                "PUT",
                JsonUtil.toJson(bundle),
                NO_HEADERS,
                NO_HANDLER
            )
            JsonUtil.fromJson(responseText, NewSendMessageResponse::class.java)
        } catch (nfe: NotFoundException) {
            throw UnregisteredUserException(recipient.id, nfe)
        }
    }

    @Throws(NonSuccessfulResponseCodeException::class, PushNetworkException::class, MalformedResponseException::class)
    private fun makeServiceRequest(urlFragment: String, method: String, jsonBody: String?): String {
        return makeServiceRequest(urlFragment, method, jsonBody, NO_HEADERS, NO_HANDLER)
    }

    @Throws(NonSuccessfulResponseCodeException::class, PushNetworkException::class, MalformedResponseException::class)
    private fun makeServiceRequest(
        urlFragment: String,
        method: String,
        jsonBody: String?,
        headers: Map<String, String>,
        responseCodeHandler: ResponseCodeHandler
    ): String {
        val responseBody = makeServiceBodyRequest(urlFragment, method, jsonRequestBody(jsonBody), headers, responseCodeHandler)
        return readBodyString(responseBody)
    }

    @Throws(NonSuccessfulResponseCodeException::class, PushNetworkException::class, MalformedResponseException::class)
    private fun makeServiceRequest(
        urlFragment: String,
        method: String,
        body: RequestBody?,
        headers: Map<String, String>,
        responseCodeHandler: ResponseCodeHandler
    ): Response {
        return makeServiceBodyRequest(urlFragment, method, body, headers, responseCodeHandler, closeBody = false)
    }

    @Throws(NonSuccessfulResponseCodeException::class, PushNetworkException::class, MalformedResponseException::class)
    private fun makeServiceBodyRequest(
        urlFragment: String,
        method: String,
        body: RequestBody?,
        headers: Map<String, String>,
        responseCodeHandler: ResponseCodeHandler
    ): ResponseBody? {
        // Pass closeBody=false to let caller handle body closing (ResponseBody.string() auto-closes)
        return makeServiceBodyRequest(urlFragment, method, body, headers, responseCodeHandler, closeBody = false).body
    }

    @Throws(NonSuccessfulResponseCodeException::class, PushNetworkException::class, MalformedResponseException::class)
    private fun makeServiceBodyRequest(
        urlFragment: String,
        method: String,
        body: RequestBody?,
        headers: Map<String, String>,
        responseCodeHandler: ResponseCodeHandler,
        closeBody: Boolean
    ): Response {
        var response: Response? = null
        try {
            response = getServiceConnection(urlFragment, method, body, headers)
            val responseCode = response.code

            when (responseCode) {
                200, 204 -> return response
            }

            // Let the response code handler deal with non-success codes first (e.g., for captcha)
            val finalResponse = response
            responseCodeHandler.handle(
                responseCode,
                response.body,
                if (response.header("Retry-After") != null) { s -> finalResponse.header(s) } else null
            )

            // Handle common error codes and throw appropriate exceptions
            validateResponse(response)

            return response
        } catch (e: NonSuccessfulResponseCodeException) {
            // NonSuccessfulResponseCodeException extends IOException, so catch it first and rethrow
            throw e
        } catch (e: IOException) {
            throw PushNetworkException(e)
        } finally {
            if (closeBody) {
                response?.body?.close()
            }
        }
    }

    /**
     * Validates the response and throws appropriate exceptions for error status codes.
     * This restores the error handling logic that was lost during the Java -> Kotlin migration.
     */
    @Throws(NonSuccessfulResponseCodeException::class, MalformedResponseException::class)
    private fun validateResponse(response: Response) {
        val responseCode = response.code
        val responseMessage = response.message

        when (responseCode) {
            401, 403 -> throw AuthorizationFailedException(responseCode, "Authorization failed!")
            404 -> {
                // Try to parse body to check for AccountOfflineException (status 10105 or 10110)
                try {
                    val bodyString = response.peekBody(Long.MAX_VALUE).string()
                    val socketResponse = JsonUtil.fromJson(bodyString, SocketResponse::class.java)
                    if (socketResponse != null && (socketResponse.status == 10105 || socketResponse.status == 10110)) {
                        throw AccountOfflineException(socketResponse.status, socketResponse.reason)
                    }
                } catch (e: Exception) {
                    // Ignore parsing errors, fall through to NotFoundException
                    if (e is AccountOfflineException) throw e
                }
                throw NotFoundException("Not found")
            }
            409 -> {
                // MismatchedDevicesException - try to parse body
                try {
                    val bodyString = response.peekBody(Long.MAX_VALUE).string()
                    val mismatchedDevices = JsonUtil.fromJson(bodyString, MismatchedDevices::class.java)
                    throw MismatchedDevicesException(mismatchedDevices)
                } catch (e: Exception) {
                    if (e is MismatchedDevicesException) throw e
                    throw NonSuccessfulResponseCodeException(responseCode, "Mismatched devices")
                }
            }
            410 -> {
                // StaleDevicesException - try to parse body
                try {
                    val bodyString = response.peekBody(Long.MAX_VALUE).string()
                    val staleDevices = JsonUtil.fromJson(bodyString, StaleDevices::class.java)
                    throw StaleDevicesException(staleDevices)
                } catch (e: Exception) {
                    if (e is StaleDevicesException) throw e
                    throw NonSuccessfulResponseCodeException(responseCode, "Stale devices")
                }
            }
            413, 429 -> {
                val retryAfterLong = Util.parseLong(response.header("Retry-After"), -1)
                val retryAfter = if (retryAfterLong != -1L) {
                    java.util.Optional.of(TimeUnit.SECONDS.toMillis(retryAfterLong))
                } else {
                    java.util.Optional.empty()
                }
                throw RateLimitException(responseCode, "Rate limit exceeded: $responseCode", retryAfter)
            }
        }

        // For any other non-success code, throw generic exception
        if (responseCode != 200 && responseCode != 202 && responseCode != 204) {
            throw NonSuccessfulResponseCodeException(responseCode, "Bad response: $responseCode $responseMessage")
        }
    }

    @Throws(PushNetworkException::class)
    private fun getServiceConnection(
        urlFragment: String,
        method: String,
        body: RequestBody?,
        headers: Map<String, String>
    ): Response {
        try {
            val okHttpClient = buildOkHttpClient()
            val call = okHttpClient.newCall(buildServiceRequest(urlFragment, method, body, headers))

            synchronized(connections) {
                connections.add(call)
            }

            try {
                return call.execute()
            } finally {
                synchronized(connections) {
                    connections.remove(call)
                }
            }
        } catch (e: IOException) {
            throw PushNetworkException(e)
        }
    }

    private fun buildOkHttpClient(): OkHttpClient {
        return client.newBuilder()
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(automaticNetworkRetry)
            .build()
    }

    private fun buildServiceRequest(
        urlFragment: String,
        method: String,
        body: RequestBody?,
        extraHeaders: Map<String, String>
    ): Request {
        // Convert WebSocket URL to HTTP URL and extract base path
        // e.g., wss://chat.test.chative.im/chat/v1/websocket/ -> https://chat.test.chative.im/chat
        val httpsUrl = serviceUrl.replace("wss://", "https://").replace("ws://", "http://")
        val url = URL(httpsUrl)

        // Extract base URL: if path contains /chat, use host + /chat as base
        val baseUrl = if (url.path.contains("/chat")) {
            "https://${url.host}/chat"
        } else {
            "https://${url.host}"
        }

        val fullUrl = baseUrl + urlFragment

        L.d { "Push service URL: $serviceUrl" }
        L.d { "Opening URL: $fullUrl" }

        val requestBuilder = Request.Builder()
        requestBuilder.url(fullUrl)
        requestBuilder.method(method, body)

        // Add configured headers
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        // Add extra headers
        extraHeaders.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        // Add Host header for /chat paths
        if (url.path.contains("/chat")) {
            requestBuilder.addHeader("Host", url.host)
        }

        return requestBuilder.build()
    }

    private fun jsonRequestBody(jsonBody: String?): RequestBody? {
        return jsonBody?.toRequestBody(JSON_MEDIA_TYPE)
    }

    // region Inner Classes

    private class DeviceCode {
        @JsonProperty
        var verificationCode: String = ""
    }

    private class ProvisioningMessage(@JsonProperty val body: String)

    private class DeviceInfoList {
        @JsonProperty
        var devices: List<DeviceInfo> = emptyList()
    }

    class RegistrationLockFailure {
        @JvmField
        @JsonProperty
        var length: Int = 0

        @JvmField
        @JsonProperty
        var timeRemaining: Long = 0

        @JvmField
        @JsonProperty
        var backupCredentials: AuthCredentials? = null
    }

    interface ResponseCodeHandler {
        @Throws(NonSuccessfulResponseCodeException::class, PushNetworkException::class)
        fun handle(responseCode: Int, body: ResponseBody?)

        @Throws(NonSuccessfulResponseCodeException::class, PushNetworkException::class)
        fun handle(responseCode: Int, body: ResponseBody?, getHeader: ((String) -> String?)?) {
            handle(responseCode, body)
        }
    }

    private class EmptyResponseCodeHandler : ResponseCodeHandler {
        override fun handle(responseCode: Int, body: ResponseBody?) {
            // No-op
        }
    }

    // endregion
}