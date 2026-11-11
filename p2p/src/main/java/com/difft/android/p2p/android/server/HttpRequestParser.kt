package com.difft.android.p2p.android.server

import com.difft.android.p2p.engine.model.ProtocolError
import okio.BufferedSource






data class HttpRequestHead(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
) {
    val contentLength: Long
        get() = headers["content-length"]?.toLongOrNull() ?: 0L

    






    val isChunked: Boolean
        get() = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true

    








    fun requireControlBody(): Long {
        val declared = headers["content-length"]
        val length = if (declared == null) 0L else declared.toLongOrNull() ?: -1L
        if (length < 0 || length > HttpRequestParser.MAX_CONTROL_BODY_BYTES) {
            throw ProtocolError.BadRequest("body too large")
        }
        return length
    }
}









object HttpRequestParser {

    



    const val MAX_HEADER_LINE_BYTES = 8 * 1024

    
    const val MAX_HEADER_COUNT = 64

    
    const val MAX_CONTROL_BODY_BYTES = 64 * 1024L

    
    fun parseHead(source: BufferedSource): HttpRequestHead {
        val lineLimit = MAX_HEADER_LINE_BYTES.toLong()
        val requestLine = source.readUtf8LineStrict(lineLimit)
        val parts = requestLine.split(' ')
        require(parts.size >= 2) { "malformed request line: $requestLine" }
        val method = parts[0]
        val target = parts[1]

        val (path, query) = splitTarget(target)

        val headers = LinkedHashMap<String, String>()
        
        var headerLines = 0
        while (true) {
            val line = source.readUtf8LineStrict(lineLimit)
            if (line.isEmpty()) break 
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
