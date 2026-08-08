package com.difft.android.websocket.internal.websocket

import com.difft.android.websocket.api.push.exceptions.MalformedResponseException
import com.difft.android.websocket.internal.ServiceResponse
import com.difft.android.websocket.internal.util.JsonUtil
import java.util.Objects
import java.util.function.Function

/**
 * A default implementation of a [ResponseMapper] that can parse most known application errors via
 * [DefaultErrorMapper] and provides basic JSON parsing of the response model if possible.
 *
 * Can be extended to add custom parsing for both the result type and the error cases.
 */
class DefaultResponseMapper<Response> private constructor(
    private val clazz: Class<Response>,
    private val customResponseMapper: CustomResponseMapper<Response>?,
    private val errorMapper: ErrorMapper
) : ResponseMapper<Response> {

    private constructor(clazz: Class<Response>) : this(clazz, null, DefaultErrorMapper.getDefault())

    override fun map(status: Int, body: String, getHeader: Function<String, String>): ServiceResponse<Response> {
        var applicationError: Throwable?
        try {
            applicationError = errorMapper.parseError(status, body, getHeader)
        } catch (e: MalformedResponseException) {
            applicationError = e
        }
        if (applicationError == null) {
            try {
                if (customResponseMapper != null) {
                    return Objects.requireNonNull(customResponseMapper.map(status, body, getHeader))
                }
                return ServiceResponse.forResult(JsonUtil.fromJsonResponse(body, clazz), status, body)
            } catch (e: MalformedResponseException) {
                applicationError = e
            }
        }
        return ServiceResponse.forApplicationError(applicationError!!, status, body)
    }

    class Builder<Value>(private val clazz: Class<Value>) {
        private val errorMapperBuilder: DefaultErrorMapper.Builder = DefaultErrorMapper.extend()
        private var customResponseMapper: CustomResponseMapper<Value>? = null

        fun withResponseMapper(responseMapper: CustomResponseMapper<Value>): Builder<Value> {
            this.customResponseMapper = responseMapper
            return this
        }

        fun withCustomError(status: Int, errorMapper: ErrorMapper): Builder<Value> {
            errorMapperBuilder.withCustom(status, errorMapper)
            return this
        }

        fun build(): ResponseMapper<Value> =
            DefaultResponseMapper(clazz, customResponseMapper, errorMapperBuilder.build())
    }

    fun interface CustomResponseMapper<T> {
        @Throws(MalformedResponseException::class)
        fun map(status: Int, body: String, getHeader: Function<String, String>): ServiceResponse<T>
    }

    companion object {
        @JvmStatic
        fun <T> getDefault(clazz: Class<T>): DefaultResponseMapper<T> = DefaultResponseMapper(clazz)

        @JvmStatic
        fun <T> extend(clazz: Class<T>): Builder<T> = Builder(clazz)
    }
}
