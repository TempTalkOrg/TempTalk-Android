package com.difft.android.network

import com.difft.android.base.utils.time.ServerTimeProvider
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * Delegating [Converter.Factory] wrapping the Gson factory: on a successful deserialize to a
 * [BaseResponse] with a positive outer `serverTimestamp`, it feeds [ServerTimeProvider]. One hook
 * covers every service; all other conversions delegate unchanged (behaviour-preserving).
 */
class ServerTimeCaptureConverterFactory(
    private val delegate: Converter.Factory
) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *>? {
        val inner = delegate.responseBodyConverter(type, annotations, retrofit) ?: return null
        return Converter<ResponseBody, Any?> { body ->
            inner.convert(body)?.also { result ->
                (result as? BaseResponse<*>)?.serverTimestamp?.takeIf { it > 0L }
                    ?.let { ts -> ServerTimeProvider.update(ts, "api") }
            }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<*, RequestBody>? =
        delegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)

    override fun stringConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<*, String>? =
        delegate.stringConverter(type, annotations, retrofit)
}
