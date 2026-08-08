package com.difft.android.websocket.internal

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.util.OptionalUtil
import com.difft.android.websocket.api.util.Preconditions
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Provide the basis for processing a [ServiceResponse] in a sharable, quasi-enforceable way. The
 * goal is to balance the readability at the call sites where the various cases are handled and
 * provide call specific information of what should be expected.
 *
 * General premise is for subclasses to override and expose (via access modifier) the types of
 * errors that should be handled when processing a response.
 *
 * This doesn't exactly enforce the handling like a checked exception would, but does hint to the
 * caller what they should be aware of as possible outcomes of processing a response.
 */
abstract class ServiceResponseProcessor<T>(response: ServiceResponse<T>) {

    @JvmField
    protected val response: ServiceResponse<T> = response

    fun getResponse(): ServiceResponse<T> = response

    fun getResult(): T {
        Preconditions.checkArgument(response.result.isPresent)
        return response.result.get()
    }

    val resultOrThrow: T
        @Throws(IOException::class)
        get() {
            if (hasResult()) {
                return getResult()
            }

            val error = getError()
            when (error) {
                is IOException -> throw error
                is RuntimeException -> throw error
                is InterruptedException, is TimeoutException -> throw IOException(error)
                else -> throw IllegalStateException("Unexpected error type for response processor", error)
            }
        }

    fun hasResult(): Boolean = response.result.isPresent

    protected fun getError(): Throwable? =
        OptionalUtil.or(response.applicationError, response.executionError).orElse(null)

    protected fun authorizationFailed(): Boolean = response.status == 401 || response.status == 403

    protected fun captchaRequired(): Boolean = response.status == 402

    protected fun notFound(): Boolean = response.status == 404

    protected fun mismatchedDevices(): Boolean = response.status == 409

    protected fun staleDevices(): Boolean = response.status == 410

    protected fun deviceLimitedExceeded(): Boolean = response.status == 411

    protected fun rateLimit(): Boolean = response.status == 413 || response.status == 429

    protected fun expectationFailed(): Boolean = response.status == 417

    protected fun registrationLock(): Boolean = response.status == 423

    protected fun proofRequired(): Boolean = response.status == 428

    protected fun deprecatedVersion(): Boolean = response.status == 499

    protected fun serverRejected(): Boolean = response.status == 508

    protected fun notSuccessful(): Boolean =
        response.status != 200 && response.status != 202 && response.status != 204

    protected fun genericIoError(): Boolean {
        val error = getError()
        if (error is NonSuccessfulResponseCodeException) {
            return false
        }
        return error is IOException || error is TimeoutException || error is InterruptedException
    }

    class DefaultProcessor<T>(response: ServiceResponse<T>) : ServiceResponseProcessor<T>(response)
}
