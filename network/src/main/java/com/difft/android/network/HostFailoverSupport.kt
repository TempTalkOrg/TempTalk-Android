package com.difft.android.network

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException

/**
 * What [HostFailoverInterceptor] knows about the network hop of one attempt. It is a request tag,
 * fed by [ExchangeObserver], and covers only the current hop: OkHttp follows a redirect below the
 * application interceptors, so a failure after one belongs to the redirect target.
 *
 * - [hopHost] is the host the current hop connected to, null until it has.
 * - [hopSent] is true once the request reached a connection, so it may have been delivered.
 * - [hopIndex] counts the redirects followed so far.
 * - [observedError] is the exception the current hop's network chain threw last. OkHttp may recover
 *   from such a failure by retrying on a new connection, and that retry can fail before it reaches
 *   [ExchangeObserver] again (a connect failure). Only a final error that is this very instance
 *   failed after the request was sent (#1396).
 */
internal class AttemptState {
    @Volatile
    var hopHost: String? = null
        private set

    @Volatile
    var hopSent: Boolean = false
        private set

    @Volatile
    var hopIndex: Int = 0
        private set

    @Volatile
    var observedError: IOException? = null
        private set

    fun enterHop(host: String) {
        hopHost = host
        hopSent = true
        observedError = null
    }

    fun failHop(error: IOException) {
        observedError = error
    }

    fun openNextHop() {
        hopIndex++
        hopHost = null
        hopSent = false
        observedError = null
    }
}

/**
 * Network interceptor that records each hop in the [AttemptState] tag of the request. It runs once
 * per network hop (pooled connections, route retries and redirect follow-ups included), after a
 * connection exists and before headers are written, which is exactly the point where a failure
 * stops being safe to resend.
 */
internal class ExchangeObserver : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val state = request.tag(AttemptState::class.java) ?: return chain.proceed(request)
        state.enterHop(request.url.host)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            state.failHop(e)
            throw e
        }
        if (response.isRedirect && response.header("Location") != null) state.openNextHop()
        return response
    }
}

/**
 * Passes [delegate] through and reports the first [IOException] thrown while its source is read,
 * unless the caller closed the body first (a cancelled download).
 */
internal class FailureObservingBody(
    private val delegate: ResponseBody,
    private val onFailure: (IOException) -> Unit,
) : ResponseBody() {

    private val observedSource: BufferedSource by lazy { ObservingSource(delegate.source()).buffer() }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = observedSource

    private inner class ObservingSource(source: Source) : ForwardingSource(source) {
        @Volatile
        private var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long = try {
            super.read(sink, byteCount)
        } catch (e: IOException) {
            if (!closed) onFailure(e)
            throw e
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
