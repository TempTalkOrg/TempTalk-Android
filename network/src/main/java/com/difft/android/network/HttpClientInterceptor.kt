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


















class HttpClientInterceptor(
    private val pinnedConnection: Boolean = true
) : Interceptor {

    






    private sealed class ResponseResult {
        
        data class Success(val response: Response) : ResponseResult()

        
        data class Failed(val exception: Exception) : ResponseResult()
    }

    override fun intercept(chain: Interceptor.Chain): Response = try {
        normalize(chain)
    } catch (e: Exception) {
        
        
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

    
    private fun Exception.describeForLog(): String =
        if (this is IOException) "${javaClass.simpleName}: $message" else stackTraceToString()

    private fun Exception.asNetworkException(): Exception = when (this) {
        is NetworkException -> this
        is HttpException -> NetworkException(code(), application.getString(R.string.chat_net_error))
        else -> IOException(application.getString(R.string.chat_net_error))
    }

    private fun handleException(e: Exception, request: Request?) {
        
        
        if (pinnedConnection && CertValidationFailureDetector.isCertValidationFailure(e)) {
            NetworkRiskNotifier.onCertValidationFailed("http:${request?.url?.host ?: "unknown"}")
        }
    }

    












    private fun processResponse(response: Response, request: Request): ResponseResult {
        return when {
            
            response.code == 204 -> {
                val protocol = response.protocol  
                response.close()
                ResponseResult.Success(createNoContentResponse(request, protocol))
            }

            
            response.isSuccessful -> {
                ResponseResult.Success(response)
            }

            
            
            
            
            response.code == 401 -> {
                ResponseResult.Success(response)
            }

            
            
            response.code >= 500 -> {
                response.close()
                ResponseResult.Failed(NetworkException(0, application.getString(R.string.chat_net_error)))
            }

            
            else -> {
                processNonSuccessResponse(response, request)
            }
        }
    }

    





    private fun processNonSuccessResponse(response: Response, request: Request): ResponseResult {
        val responseCode = response.code
        val contentType = response.body?.contentType()
        val protocol = response.protocol  

        
        val bodyString = try {
            response.body?.string()
        } catch (_: Exception) {
            response.close()
            return ResponseResult.Failed(
                NetworkException(responseCode, application.getString(R.string.chat_net_error))
            )
        }

        
        response.close()

        if (bodyString.isNullOrEmpty()) {
            return ResponseResult.Failed(
                NetworkException(responseCode, application.getString(R.string.chat_net_error))
            )
        }

        
        val jsonResponse = try {
            JSONObject(bodyString)
        } catch (_: Exception) {
            null
        }

        val reason = jsonResponse?.optString("reason", "") ?: ""
        return if (reason.isNotEmpty()) {
            
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
