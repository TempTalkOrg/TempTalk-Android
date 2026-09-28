package com.difft.android.p2p.android.server

import com.difft.android.p2p.engine.model.ProtocolError
import okio.BufferedSource

/**
 * A parsed HTTP/1.1 request head: method, path, query params, headers, and the framing info needed
 * to read the body. The body is not read here — the caller streams it from the same [BufferedSource]
 * straight into a [com.difft.android.p2p.engine.io.FileSink], so large uploads are never buffered.
 */
data class HttpRequestHead(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
) {
    val contentLength: Long
        get() = headers["content-length"]?.toLongOrNull() ?: 0L

    /**
     * True when the body is framed with `Transfer-Encoding: chunked` instead of a `Content-Length`.
     * Only the migration container upload is chunked, its length being unknown up front; the receiver
     * branches on this to pick the body framing (see [ChunkedSource] / [LimitedSource]).
     *
     * Per RFC 7230 §3.3.3, `Transfer-Encoding` wins when both headers are present.
     */
    val isChunked: Boolean
        get() = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true

    /**
     * The body length of a control request, bounded by [HttpRequestParser.MAX_CONTROL_BODY_BYTES].
     * A control body is buffered whole before anything authenticates the caller, so an unbounded
     * declared length is a free heap allocation for any peer that reaches the pairing window. The
     * `/upload` route never goes through here: its body is streamed, never buffered.
     *
     * @throws ProtocolError.BadRequest when the declared length is over the cap, negative, or not a
     * length at all (a value wider than [Long] is as unbounded as an over-cap one).
     */
    fun requireControlBody(): Long {
        val declared = headers["content-length"]
        val length = if (declared == null) 0L else declared.toLongOrNull() ?: -1L
        if (length < 0 || length > HttpRequestParser.MAX_CONTROL_BODY_BYTES) {
            throw ProtocolError.BadRequest("body too large")
        }
        return length
    }
}

/**
 * Minimal HTTP/1.1 request-head parser over an Okio [BufferedSource].
 *
 * Scope is deliberately narrow: a request line, CRLF-delimited headers, then either a
 * `Content-Length`-framed body or, for the migration container upload only, a
 * `Transfer-Encoding: chunked` body. Only the head is read here; [HttpRequestHead.isChunked] tells
 * the caller which body framing to use ([LimitedSource] or [ChunkedSource]).
 */
object HttpRequestParser {

    /**
     * Longest request or header line accepted. Okio's bounded line read throws `EOFException` past
     * it, which the caller already treats as a connection that never delivered a request.
     */
    const val MAX_HEADER_LINE_BYTES = 8 * 1024

    /** Most header lines accepted on one request. */
    const val MAX_HEADER_COUNT = 64

    /** Largest body accepted on a control route — see [HttpRequestHead.requireControlBody]. */
    const val MAX_CONTROL_BODY_BYTES = 64 * 1024L

    /** Reads + parses the request line and headers. Leaves the body unread on [source]. */
    fun parseHead(source: BufferedSource): HttpRequestHead {
        val lineLimit = MAX_HEADER_LINE_BYTES.toLong()
        val requestLine = source.readUtf8LineStrict(lineLimit)
        val parts = requestLine.split(' ')
        require(parts.size >= 2) { "malformed request line: $requestLine" }
        val method = parts[0]
        val target = parts[1]

        val (path, query) = splitTarget(target)

        val headers = LinkedHashMap<String, String>()
        // Counted as lines, not map entries: repeating one name must trip the cap just the same.
        var headerLines = 0
        while (true) {
            val line = source.readUtf8LineStrict(lineLimit)
            if (line.isEmpty()) break // CRLF separating headers from body
            if (++headerLines > MAX_HEADER_COUNT) throw ProtocolError.BadRequest("too many headers")
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }
        return HttpRequestHead(method, path, query, headers)
    }

    private fun splitTarget(target: String): Pair<String, Map<String, String>> {
        val q = target.indexOf('?')
        if (q < 0) return target to emptyMap()
        val path = target.substring(0, q)
        val queryString = target.substring(q + 1)
        val params = LinkedHashMap<String, String>()
        queryString.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val eq = pair.indexOf('=')
            if (eq < 0) {
                params[urlDecode(pair)] = ""
            } else {
                params[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
            }
        }
        return path to params
    }

    private fun urlDecode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
