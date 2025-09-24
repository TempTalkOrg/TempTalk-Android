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
 * 网络异常拦截及错误日志上报
 */
class HttpErrorReportInterceptor : Interceptor {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        var wsTokenManager: WsTokenManager

        var urlManager: UrlManager
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
            if (response.code == 204) {// 处理204 No Content响应
                response = createNoContentResponse(request)
            } else if (!isSuccessResponse(response)) {
                val originalResponse = response
                val originalResponseBody = response.body

                recordWithCode(originalResponse.code, request)

                if (shouldRetry(response)) {
                    response.close()
                    response = changeHostAndReSendRequest(request, chain)
                    if (response != null) return response
                } else if (response.code == 401) {
                    var newRequest: Request?
                    runBlocking {
                        newRequest = updateTokenIfNeeded(request)
                    }
                    newRequest?.let {
                        response?.close()
                        return chain.proceed(it)
                    }
                } else {
                    // 只要不是2xx，body不是标准JSON（没有reason字段），直接抛NetworkException
                    try {
                        val bodyString = originalResponseBody?.string()
                        if (bodyString != null) {
                            val jsonResponse = try {
                                JSONObject(bodyString)
                            } catch (_: Exception) {
                                null
                            }
                            val reason = jsonResponse?.optString("reason", "") ?: ""
                            if (reason.isNotEmpty()) {
                                val newResponseBody = bodyString.toResponseBody(originalResponseBody.contentType())
                                return originalResponse.newBuilder()
                                    .code(200)
                                    .body(newResponseBody)
                                    .build()
                            } else {
                                throw NetworkException(response.code, application.getString(R.string.chat_net_error))
                            }
                        } else {
                            throw NetworkException(response.code, application.getString(R.string.chat_net_error))
                        }
                    } catch (e: Exception) {
                        throw NetworkException(response.code, application.getString(R.string.chat_net_error))
                    }
                }
            }
        } catch (e: Exception) {
            lastException = e
            handleException(e, request)
            if (isRetryAbleException(e)) {
                response?.close()
                response = changeHostAndReSendRequest(request, chain)
                if (response != null) return response
            }
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

    private fun isSuccessResponse(response: Response?): Boolean {
        return response != null && response.isSuccessful
    }

    private fun shouldRetry(response: Response?): Boolean {
        val code = response?.code ?: 0
        return code >= 500 || code == 404
    }

    private fun isRetryAbleException(e: Exception): Boolean {
        return e is IOException
    }

    private fun handleException(e: Exception, request: Request?) {
        e.printStackTrace()
        if (needHandleException(e)) {
            recordError(e, request)
        }
    }

    private fun needHandleException(e: Exception) = "Canceled".equals(e.message, true).not() && "Socket closed".equals(e.message, true).not()

    private fun changeHostAndReSendRequest(request: Request, chain: Interceptor.Chain): Response? {
        UrlManager.changeToOtherHost()
        val originalUrl = request.url
        val originalHost = originalUrl.host
        val hosts = EntryPointAccessors.fromApplication<EntryPoint>(application).urlManager.findHostsByOldHost(originalHost)
        val attemptedHosts = mutableSetOf<String>()
        for (host in hosts) {
            if (host != originalHost && host !in attemptedHosts) {
                attemptedHosts.add(host)

                val newUrl = originalUrl.newBuilder().host(host).build()
                val newRequest = request.newBuilder().url(newUrl).build()
                try {
                    val newResponse = chain.proceed(newRequest)
                    if (newResponse.code < 500) {
                        return newResponse
                    } else {
                        newResponse.close()
                    }
                } catch (e: Exception) {
                    lastException = e
                    e.printStackTrace()
                    handleException(e, request)
                }
            }
        }
        return null
    }

    private fun recordError(e: Exception, request: Request?) {
        val msg = buildString {
            appendLine(networkStatus())
            appendLine(requestUrl(request))
            appendLine(requestMethod(request))
            append(e.message)
        }

        record(NetworkException(message = msg))
    }

    private fun recordWithCode(code: Int, request: Request) {
        val msg = buildString {
            appendLine("code=$code")
            appendLine(networkStatus())
            appendLine(requestUrl(request))
            appendLine(requestMethod(request))
        }

        record(NetworkException(errorCode = code, message = msg))
    }

    private fun record(e: Throwable) {
        L.w { "[Net error]:$e" }
        FirebaseCrashlytics.getInstance().recordException(e)
    }

    private fun networkStatus(): String = "网络类型=${NetUtil.getNetWorkSumary()}"

    private fun requestUrl(request: Request?): String = "url=${request?.url?.toString() ?: ""}"

    private fun requestMethod(request: Request?): String = "method=${request?.method ?: ""}"


//    private fun requestHeaders(headers: Headers?): String {
//        if (headers == null) return "header="
//        val result: StringBuilder = StringBuilder()
//        result.append(headers[""]).append(": ").append(headers[""]).append(";")
//        return headers.toString()
//    }

//    private fun requestBody(request: Request?): String {
//        val info = try {
//            val length = request?.body?.contentLength() ?: -1
//            if (length <= 0) return ""
//
//            val requestBody = request?.body
//            val buffer = Buffer()
//            requestBody?.writeTo(buffer)
//            val charset: Charset = Charset.forName("UTF-8")
//            buffer.readString(charset)
//        } catch (e: Exception) {
//            e.printStackTrace()
//            ""
//        }
//        return "body=$info"
//    }

//    private fun createErrorResponse(request: Request): Response {
//        val errorMessage = application.getString(R.string.chat_net_error)
//        val errorResponse = BaseResponse(
//            ver = 1,
//            status = -1,
//            reason = errorMessage,
//            data = null,
//            networkException = networkException
//        )
//        val errorJson = Gson().toJson(errorResponse)
//        val errorMediaType = "application/json".toMediaTypeOrNull()
//        val errorResponseBody = errorJson.toResponseBody(errorMediaType)
//
//        return Response.Builder()
//            .code(200)
//            .message(errorMessage)
//            .protocol(Protocol.HTTP_1_1)
//            .body(errorResponseBody)
//            .request(request)
//            .build()
//    }

    private fun createNoContentResponse(request: Request): Response {
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
            .protocol(Protocol.HTTP_1_1)
            .body(errorResponseBody)
            .request(request)
            .build()
    }
}
