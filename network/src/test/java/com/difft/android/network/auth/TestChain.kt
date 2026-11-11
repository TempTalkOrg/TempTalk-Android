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


internal class SentRequests {
    val all = mutableListOf<Request>()

    
    val bodies = mutableListOf<CloseTrackingBody>()
    val single: Request get() = all.single()
    val first: Request get() = all.first()
    val size: Int get() = all.size
}






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
