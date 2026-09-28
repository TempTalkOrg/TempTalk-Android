package com.difft.android.network.auth

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource

/** Requests an interceptor actually handed to `chain.proceed`, in order. */
internal class SentRequests {
    val all = mutableListOf<Request>()

    /** Body of the response each `proceed` answered with, same order as [all]. */
    val bodies = mutableListOf<CloseTrackingBody>()
    val single: Request get() = all.single()
    val first: Request get() = all.first()
    val size: Int get() = all.size
}

/**
 * `ResponseBody` that records whether it was closed, so a test can assert the interceptor released
 * the connection of a response it did not return. The buffer is a field rather than a fresh
 * `Buffer()` per `source()` call, or `close()` would drain a different sink than a reader saw.
 */
internal class CloseTrackingBody : ResponseBody() {
    private val buffer = Buffer().writeUtf8("stub")

    var closed = false
        private set

    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = buffer.size
    override fun source(): BufferedSource = buffer

    override fun close() {
        closed = true
        super.close()
    }
}

/**
 * A mocked `Interceptor.Chain` — the precedent from `ProxyHostInterceptorTest` — that records every
 * request and answers each `proceed` with the next code in [codes] (the last code repeats).
 */
internal fun chainOf(request: Request, vararg codes: Int): Pair<Interceptor.Chain, SentRequests> {
    val sent = SentRequests()
    val chain = mockk<Interceptor.Chain>()
    every { chain.request() } returns request
    every { chain.proceed(any()) } answers {
        val proceeded = firstArg<Request>()
        sent.all += proceeded
        val body = CloseTrackingBody()
        sent.bodies += body
        val code = codes.getOrElse(sent.all.size - 1) { codes.last() }
        Response.Builder()
            .request(proceeded)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body(body)
            .build()
    }
    return chain to sent
}
