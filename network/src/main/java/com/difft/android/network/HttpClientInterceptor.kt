package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.network.CertValidationFailureDetector
import com.difft.android.base.network.NetworkRiskNotifier
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
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
 * Response normalization: 204 No Content becomes a standard [BaseResponse], a 3xx/4xx carrying a
 * `reason` becomes a 200, any other non-success becomes a [NetworkException], and a 5xx that
 * [HostFailoverInterceptor] could not resolve on any host becomes `NetworkException(0)`.
 *
 * Host failover is deliberately not here: [HostFailoverInterceptor] sits inside this interceptor,
 * so it sees the raw response or exception and this one normalizes only the final result (#1396).
 *
 * Auth is deliberately not here: [HeaderInterceptor] owns the credential and the 401 policy, so a
 * 401 passes straight through this interceptor (issue #1231).
 *
 * @param pinnedConnection True when this client pins trust to the embedded chative CA
 * (useCustomCa). Only failures on such channels are treated as a possible MITM attack;
 * system-CA clients (public CDN/OSS) can legitimately hit certificate errors.
 */
class HttpClientInterceptor(
    private val pinnedConnection: Boolean = true
) : Interceptor {

    /**
     * Response processing result.
     *
     * Close responsibility:
     * - Success: Caller is responsible for closing the returned response
     * - Failed: Original response has been closed
     */
    private sealed class ResponseResult {
        /** Processing succeeded, caller is responsible for closing the returned response */
        data class Success(val response: Response) : ResponseResult()

        /** Processing failed, original response has been closed */
        data class Failed(val exception: Exception) : ResponseResult()
    }

    override fun intercept(chain: Interceptor.Chain): Response = try {
        normalize(chain)
    } catch (e: Exception) {
        // Callers log their own failure; for I/O errors one line with host and cause is enough,
        // anything else is unexpected and keeps its stack.
        L.w { "[HttpClientInterceptor] request failed host=${chain.request().url.host} ${e.describeForLog()}" }
        handleException(e, chain.request())
        throw e.asNetworkException()
    }

    private fun normalize(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return when (val result = processResponse(chain.proceed(request), request)) {
            is ResponseResult.Success -> result.response
            is ResponseResult.Failed -> throw result.exception
        }
    }

    /** I/O failures are routine and get one line; anything else keeps its stack for location. */
    private fun Exception.describeForLog(): String =
        if (this is IOException) "${javaClass.simpleName}: $message" else stackTraceToString()

    private fun Exception.asNetworkException(): Exception = when (this) {
        is NetworkException -> this
        is HttpException -> NetworkException(code(), application.getString(R.string.chat_net_error))
        else -> IOException(application.getString(R.string.chat_net_error))
    }

    private fun handleException(e: Exception, request: Request?) {
        // On a pinned channel a certificate validation failure is a possible MITM attack.
        // Detection is centralized here because every catch path funnels through handleException.
        if (pinnedConnection && CertValidationFailureDetector.isCertValidationFailure(e)) {
            NetworkRiskNotifier.onCertValidationFailed("http:${request?.url?.host ?: "unknown"}")
        }
    }

    /**
     * Unified response processing, including:
     * - 204 No Content: Convert to standard BaseResponse
     * - 2xx Success: Return directly
     * - 401 Unauthorized: Pass through to [HeaderInterceptor]
     * - >= 500 Server Error: Failover already tried the other hosts, so it is the final failure
     * - Other non-2xx with reason field: Convert code to 200 and return
     * - Other non-2xx without reason: Return failure
     *
     * @param response Original response
     * @param request Original request (used for creating new Response)
     * @return ResponseResult Processing result
     */
    private fun processResponse(response: Response, request: Request): ResponseResult {
        return when {
            // 204 No Content - Convert to standard BaseResponse
            response.code == 204 -> {
                val protocol = response.protocol  // Save protocol before close
                response.close()
                ResponseResult.Success(createNoContentResponse(request, protocol))
            }

            // 2xx Success - Return directly
            response.isSuccessful -> {
                ResponseResult.Success(response)
            }

            // 401 is HeaderInterceptor's business (it owns the one auth style and the one retry).
            // Checked BEFORE processNonSuccessResponse so a 401 carrying a `reason` can never be
            // rewritten to 200 and hide the rejection. Body deliberately NOT closed — it travels
            // up the chain. Issue #1231.
            response.code == 401 -> {
                ResponseResult.Success(response)
            }

            // >= 500 Server Error - HostFailoverInterceptor has already marked the host and tried the
            // others; what reaches here is the last answer.
            response.code >= 500 -> {
                response.close()
                ResponseResult.Failed(NetworkException(0, application.getString(R.string.chat_net_error)))
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
        val errorJson = globalServices.gson.toJson(errorResponse)
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
