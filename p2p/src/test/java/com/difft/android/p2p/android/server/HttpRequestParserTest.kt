package com.difft.android.p2p.android.server

import com.difft.android.p2p.engine.model.ProtocolError
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException

/** T-T4 — minimal HTTP/1.1 request-head parser (design §13, Risk R1 de-risk). */
class HttpRequestParserTest {

    @Test
    fun `parses a real-LocalSend-shaped upload request line, query, and headers`() {
        val raw = "POST /api/localsend/v2/upload?sessionId=abc&fileId=f1&token=deadbeef HTTP/1.1\r\n" +
            "Host: 192.168.1.23:53317\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: 1024\r\n" +
            "\r\n"
        val source = Buffer().writeUtf8(raw)
        val head = HttpRequestParser.parseHead(source)

        assertEquals("POST", head.method)
        assertEquals("/api/localsend/v2/upload", head.path)
        assertEquals("abc", head.query["sessionId"])
        assertEquals("f1", head.query["fileId"])
        assertEquals("deadbeef", head.query["token"])
        assertEquals(1024L, head.contentLength)
        assertEquals("application/octet-stream", head.headers["content-type"])
    }

    @Test
    fun `parses prepare-upload with url-encoded pin and leaves body unread`() {
        val raw = "POST /api/localsend/v2/prepare-upload?pin=123456 HTTP/1.1\r\n" +
            "Content-Length: 5\r\n\r\n" +
            "HELLO"
        val source = Buffer().writeUtf8(raw)
        val head = HttpRequestParser.parseHead(source)

        assertEquals("/api/localsend/v2/prepare-upload", head.path)
        assertEquals("123456", head.query["pin"])
        assertEquals(5L, head.contentLength)
        // Body is left intact on the source for streaming.
        assertEquals("HELLO", source.readByteString(5).utf8())
    }

    @Test
    fun `handles request with no query string`() {
        val raw = "POST /api/localsend/v2/register HTTP/1.1\r\nContent-Length: 0\r\n\r\n"
        val head = HttpRequestParser.parseHead(Buffer().writeUtf8(raw))
        assertEquals("/api/localsend/v2/register", head.path)
        assertEquals(emptyMap<String, String>(), head.query)
        assertEquals(0L, head.contentLength)
    }

    @Test
    fun `detects chunked transfer-encoding (migration container upload framing)`() {
        val raw = "POST /api/localsend/v2/upload?sessionId=s&fileId=f&token=t HTTP/1.1\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n"
        val head = HttpRequestParser.parseHead(Buffer().writeUtf8(raw))
        assertEquals(true, head.isChunked)
    }

    @Test
    fun `content-length request is NOT chunked (oracle path unchanged)`() {
        val raw = "POST /api/localsend/v2/upload?sessionId=s&fileId=f&token=t HTTP/1.1\r\n" +
            "Content-Length: 1024\r\n\r\n"
        val head = HttpRequestParser.parseHead(Buffer().writeUtf8(raw))
        assertEquals(false, head.isChunked)
        assertEquals(1024L, head.contentLength)
    }

    @Test
    fun `a header line past the cap ends the read instead of buffering it`() {
        val raw = "POST /api/localsend/v2/register HTTP/1.1\r\n" +
            "X-Big: " + "a".repeat(HttpRequestParser.MAX_HEADER_LINE_BYTES + 1) + "\r\n\r\n"
        assertThrows(EOFException::class.java) { HttpRequestParser.parseHead(Buffer().writeUtf8(raw)) }
    }

    @Test
    fun `more header lines than the cap is a bad request`() {
        val raw = buildString {
            append("POST /api/localsend/v2/register HTTP/1.1\r\n")
            // Repeating one name too: the cap counts lines, not distinct headers.
            repeat(HttpRequestParser.MAX_HEADER_COUNT + 1) { append("X-Pad: $it\r\n") }
            append("\r\n")
        }
        val error = assertThrows(ProtocolError.BadRequest::class.java) {
            HttpRequestParser.parseHead(Buffer().writeUtf8(raw))
        }
        assertEquals("too many headers", error.detail)
    }

    @Test
    fun `a control body is accepted up to the cap and refused past it`() {
        assertEquals(0L, controlBody(null))
        assertEquals(HttpRequestParser.MAX_CONTROL_BODY_BYTES, controlBody("${HttpRequestParser.MAX_CONTROL_BODY_BYTES}"))

        listOf("${HttpRequestParser.MAX_CONTROL_BODY_BYTES + 1}", "-1", "99999999999999999999999").forEach { declared ->
            val error = assertThrows(ProtocolError.BadRequest::class.java) { controlBody(declared) }
            assertEquals("body too large", error.detail)
        }
    }

    /** The bounded length of a control request declaring [contentLength] (absent when null). */
    private fun controlBody(contentLength: String?): Long {
        val raw = "POST /api/localsend/v2/register HTTP/1.1\r\n" +
            (contentLength?.let { "Content-Length: $it\r\n" } ?: "") + "\r\n"
        return HttpRequestParser.parseHead(Buffer().writeUtf8(raw)).requireControlBody()
    }
}
