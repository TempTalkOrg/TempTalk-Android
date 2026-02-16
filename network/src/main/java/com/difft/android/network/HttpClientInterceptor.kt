package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import com.difft.android.network.config.WsTokenManager
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.Gson
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException

/**
 * HTTP Client Interceptor
 *
 * Responsibilities:
 * 1. Token management - Refresh and update auth tokens automatically
 * 2. Host failover - Switch to backup hosts when request fails (response code not in [100, 499])
 * 3. Response handling - Handle 204 No Content, transform non-success responses
 * 4. Error reporting - Record network errors to Firebase Crashlytics
 */
class HttpClientInterceptor : Interceptor {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val wsTokenManager: WsTokenManager

        val urlManager: UrlManager
    }

    /**
     * Response processing result.
     *
     * Close responsibility:
     * - Success: Caller is responsible for closing the returned response
     * - NeedsTokenRefresh: Original response has been closed
     * - NeedsHostRetry: Original response has been closed
     * - Failed: Original response has been closed
     */
    private sealed class ResponseResult {
        /** Processing succeeded, caller is responsible for closing the returned response */
        data class Success(val response: Response) : ResponseResult()

        /** Token refresh needed, original response has been closed */
        object NeedsTokenRefresh : ResponseResult()

        /** Host retry needed (code >= 500), original response has been closed */
        object NeedsHostRetry : ResponseResult()

        /** Processing failed, original response has been closed */
        data class Failed(val exception: Exception) : ResponseResult()
    }

    private var lastException: Exception? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response: Response? = null
        lastException = null
        try {
            runBlocking {
                request = updateTokenIfNeeded(request)
            }

            response = chain.proceed(request)

            // Save original response code for error logging (processResponse may close response)
            val originalCode = response.code

            when (val result = processResponse(response, request)) {
                is ResponseResult.Success -> {
                    // Special cases (204, non-2xx with reason) are tracked inside processResponse
                    // using SpecialResponseTrackingException, no need to record here
                    response = result.response
                }

                is ResponseResult.NeedsTokenRefresh -> {
                    recordNetworkError(originalCode, request)
                    // Force refresh token and retry with original host
                    val newRequest = runBlocking { forceUpdateToken(request) }
                    if (newRequest == null) {
                        // Token refresh failed, throw exception
                        throw NetworkException(401, application.getString(R.string.chat_net_error))
                    }
                    val retryResponse = chain.proceed(newRequest)
                    // Process retry response as well
                    when (val retryResult = processResponse(retryResponse, newRequest)) {
                        is ResponseResult.Success -> return retryResult.response
                        is ResponseResult.NeedsTokenRefresh -> {
                            // Token still rejected after force refresh, throw exception
                            throw NetworkException(401, application.getString(R.string.chat_net_error))
                        }

                        is ResponseResult.NeedsHostRetry -> {
                            // Server error after token refresh, try other hosts
                            response = changeHostAndReSendRequest(newRequest, chain)
                            if (response != null) return response
                        }

                        is ResponseResult.Failed -> throw retryResult.exception
                    }
                }

                is ResponseResult.NeedsHostRetry -> {
                    recordNetworkError(originalCode, request)
                    // Switch to backup host and retry
                    response = changeHostAndReSendRequest(request, chain)
                    if (response != null) return response
                }

                is ResponseResult.Failed -> {
                    recordNetworkError(originalCode, request)
                    throw result.exception
                }
            }
        } catch (e: Exception) {
            L.w { "[HttpClientInterceptor] error: ${e.stackTraceToString()}" }
            lastException = e
            handleException(e, request)
        }

        return response ?: run {
            val errorMsg = application.getString(R.string.chat_net_error)
            throw when (lastException) {
                is NetworkException -> lastException as NetworkException
                is HttpException -> NetworkException((lastException as HttpException).code(), errorMsg)
                else -> IOException(errorMsg)
            }
        }
    }

    private suspend fun updateTokenIfNeeded(request: Request): Request {
        val tokenManager = EntryPointAccessors.fromApplication<EntryPoint>(application).wsTokenManager
        val currentToken = request.header("Authorization")
        if (currentToken?.startsWith("basic", true) == false) {
            tokenManager.refreshTokenIfNeeded()

            val newToken = tokenManager.getToken()
            if (newToken != null) {
                return request.newBuilder().removeHeader("Authorization").addHeader("Authorization", newToken).build()
            }
        }
        return request
    }

    /**
     * Force refresh token and rebuild request.
     * Use this when server returns 401 (token rejected by server).
     *
     * @return New request with refreshed token, or null if refresh failed
     */
    private suspend fun forceUpdateToken(request: Request): Request? {
        val tokenManager = EntryPointAccessors.fromApplication<EntryPoint>(application).wsTokenManager
        val currentToken = request.header("Authorization")
        if (currentToken?.startsWith("basic", true) == true) {
            // Basic auth request, no need to refresh token
            return null
        }
        val newToken = tokenManager.forceRefreshToken()
        return if (newToken.isNotEmpty()) {
            request.newBuilder()
                .removeHeader("Authorization")
                .addHeader("Authorization", newToken)
                .build()
        } else {
            null
        }
    }

    private fun handleException(e: Exception, request: Request?) {
        if (needHandleException(e)) {
            recordNetworkException(e, request)
        }
    }

    /**
     * Determine if exception should be recorded to Firebase.
     * Excludes:
     * - NetworkException: Already recorded via recordNetworkError before throwing
     * - Canceled/Socket closed: User-initiated or expected disconnection
     */
    private fun needHandleException(e: Exception): Boolean {
        // NetworkException is already recorded via recordNetworkError, skip to avoid duplicate
        if (e is NetworkException) return false
        // Skip user-initiated cancellation and expected socket closure
        if ("Canceled".equals(e.message, true)) return false
        if ("Socket closed".equals(e.message, true)) return false
        return true
    }

    private fun changeHostAndReSendRequest(request: Request, chain: Interceptor.Chain): Response? {
        val originalUrl = request.url
        val originalHost = originalUrl.host
        val urlManager = EntryPointAccessors.fromApplication<EntryPoint>(application).urlManager
        // Record failed host so other hosts are prioritized next time
        urlManager.recordFailedHost(originalHost)
        val hosts = urlManager.findHostsByOldHost(originalHost)
        val attemptedHosts = mutableSetOf<String>()
        for (host in hosts) {
            if (host != originalHost && host !in attemptedHosts) {
                attemptedHosts.add(host)

                val newUrl = originalUrl.newBuilder().host(host).build()
                val newRequest = request.newBuilder().url(newUrl).build()
                try {
                    val newResponse = chain.proceed(newRequest)
                    when (val result = processResponse(newResponse, newRequest)) {
                        is ResponseResult.Success -> {
                            // Success (including 204 conversion, 2xx, reason extraction), return directly
                            return result.response
                        }

                        is ResponseResult.NeedsTokenRefresh -> {
                            // Force refresh token and retry once with current backup host
                            try {
                                val refreshedRequest = runBlocking { forceUpdateToken(newRequest) }
                                if (refreshedRequest != null) {
                                    val retryResponse = chain.proceed(refreshedRequest)
                                    when (val retryResult = processResponse(retryResponse, refreshedRequest)) {
                                        is ResponseResult.Success -> return retryResult.response
                                        else -> {
                                            // Retry still failed, continue to next host
                                        }
                                    }
                                }
                                // Token refresh failed, continue to next host
                            } catch (e: Exception) {
                                lastException = e
                                L.w { "[HttpClientInterceptor] error: ${e.stackTraceToString()}" }
                                handleException(e, newRequest)
                                // Continue to next host
                            }
                        }

                        is ResponseResult.NeedsHostRetry -> {
                            // Current backup host also returned >= 500, continue to next host
                        }

                        is ResponseResult.Failed -> {
                            // Processing failed (3xx/4xx without reason), continue to next host
                        }
                    }
                } catch (e: Exception) {
                    lastException = e
                    L.w { "[HttpClientInterceptor] error: ${e.stackTraceToString()}" }
                    handleException(e, request)
                }
            }
        }
        return null
    }

    // ==================== Firebase Recording Methods ====================

    /**
     * Unified method to record exception to Firebase Crashlytics.
     * All recording methods should go through this for consistency.
     */
    private fun recordToFirebase(e: Throwable) {
        L.w { "[Network] $e" }
        FirebaseCrashlytics.getInstance().recordException(e)
    }

    /**
     * Record network error with HTTP status code.
     * Used for real errors: 401, 403, 500, etc.
     */
    private fun recordNetworkError(code: Int, request: Request) {
        val msg = buildString {
            appendLine("code=$code")
            appendLine(networkStatus())
            appendLine(requestUrl(request))
            appendLine(requestMethod(request))
        }
        recordToFirebase(NetworkException(errorCode = code, message = msg))
    }

    /**
     * Record network exception (IOException, SocketException, etc).
     * Used for connection failures, timeouts, etc.
     */
    private fun recordNetworkException(e: Exception, request: Request?) {
        val msg = buildString {
            appendLine(networkStatus())
            appendLine(requestUrl(request))
            appendLine(requestMethod(request))
            append(e.message)
        }
        recordToFirebase(NetworkException(message = msg))
    }

    /**
     * Record special response for backend improvement tracking.
     * These are NOT errors, but tracking data for backend to fix non-standard APIs.
     * Firebase will group these separately from NetworkException.
     */
    private fun recordSpecialResponse(type: String, request: Request, extraInfo: String? = null) {
        val msg = buildString {
            appendLine(requestUrl(request))
            appendLine(requestMethod(request))
            extraInfo?.let { appendLine(it) }
        }
        recordToFirebase(SpecialResponseTrackingException(type, msg))
    }

    private fun networkStatus(): String = "network=${NetUtil.getNetWorkSummary()}"

    private fun requestUrl(request: Request?): String = "url=${request?.url?.toString() ?: ""}"

    private fun requestMethod(request: Request?): String = "method=${request?.method ?: ""}"

    /**
     * Unified response processing, including:
     * - 204 No Content: Convert to standard BaseResponse
     * - 2xx Success: Return directly
     * - 401 Unauthorized: Need token refresh and retry
     * - >= 500 Server Error: Need host switch and retry
     * - Other non-2xx with reason field: Convert code to 200 and return
     * - Other non-2xx without reason: Return failure
     *
     * @param response Original response
     * @param request Original request (used for creating new Response and logging)
     * @return ResponseResult Processing result
     */
    private fun processResponse(response: Response, request: Request): ResponseResult {
        return when {
            // 204 No Content - Convert to standard BaseResponse
            response.code == 204 -> {
//                // Track for backend improvement: should return standard BaseResponse
//                recordSpecialResponse(
//                    SpecialResponseTrackingException.RESPONSE_204_NO_CONTENT,
//                    request
//                )
                val protocol = response.protocol  // Save protocol before close
                response.close()
                ResponseResult.Success(createNoContentResponse(request, protocol))
            }

            // 2xx Success - Return directly
            response.isSuccessful -> {
                ResponseResult.Success(response)
            }

            // 401 Unauthorized - Need token refresh
            response.code == 401 -> {
                response.close()
                ResponseResult.NeedsTokenRefresh
            }

            // >= 500 Server Error - Need host switch
            response.code >= 500 -> {
                response.close()
                ResponseResult.NeedsHostRetry
            }

            // Other non-2xx (3xx/4xx) - Try to extract reason field
            else -> {
                processNonSuccessResponse(response, request)
            }
        }
    }

    /**
     * Process non-success response (3xx/4xx, excluding 401).
     * Try to extract reason field from response body, convert to 200 response if exists.
     *
     * @return ResponseResult Processing result, original response will be closed in this method
     */
    private fun processNonSuccessResponse(response: Response, request: Request): ResponseResult {
        val responseCode = response.code
        val contentType = response.body?.contentType()
        val protocol = response.protocol  // Save protocol before close

        // Try to read body
        val bodyString = try {
            response.body?.string()
        } catch (_: Exception) {
            response.close()
            return ResponseResult.Failed(
                NetworkException(responseCode, application.getString(R.string.chat_net_error))
            )
        }

        // After body is read, original response is consumed, close to release resources
        response.close()

        if (bodyString.isNullOrEmpty()) {
            return ResponseResult.Failed(
                NetworkException(responseCode, application.getString(R.string.chat_net_error))
            )
        }

        // Try to parse JSON and extract reason
        val jsonResponse = try {
            JSONObject(bodyString)
        } catch (_: Exception) {
            null
        }

        val reason = jsonResponse?.optString("reason", "") ?: ""
        return if (reason.isNotEmpty()) {
//            // Track for backend improvement: should return 200 directly
//            recordSpecialResponse(
//                SpecialResponseTrackingException.RESPONSE_NON_2XX_WITH_REASON,
//                request,
//                "originalCode=$responseCode"
//            )
            // Has reason field, convert to 200 response
            val newResponseBody = bodyString.toResponseBody(contentType)
            val newResponse = Response.Builder()
                .code(200)
                .message("OK")
                .protocol(protocol)
                .request(request)
                .body(newResponseBody)
                .build()
            ResponseResult.Success(newResponse)
        } else {
            // No reason field, return failure
            ResponseResult.Failed(
                NetworkException(responseCode, application.getString(R.string.chat_net_error))
            )
        }
    }

    private fun createNoContentResponse(request: Request, protocol: Protocol): Response {
        val errorResponse = BaseResponse(
            ver = 1,
            status = 0,
            reason = null,
            data = null
        )
        val errorJson = Gson().toJson(errorResponse)
        val errorMediaType = "application/json".toMediaTypeOrNull()
        val errorResponseBody = errorJson.toResponseBody(errorMediaType)

        return Response.Builder()
            .code(200)
            .message("No Content")
            .protocol(protocol)
            .body(errorResponseBody)
            .request(request)
            .build()
    }
}
