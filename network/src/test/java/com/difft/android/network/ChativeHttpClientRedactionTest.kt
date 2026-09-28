package com.difft.android.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a DEBUG build's HTTP log may contain (issue #1231). Debug logs are attached to internal bug
 * reports, so the `Authorization` header — the password-equivalent Basic credential or the account
 * JWT — must never appear in one, and clients whose request body carries a credential must not log
 * bodies at all.
 *
 * Exercises the REAL [buildDebugLogger] inside a real OkHttp chain; an application interceptor
 * answers every call itself, so there is no network and no MockWebServer.
 */
class ChativeHttpClientRedactionTest {

    private val lines = mutableListOf<String>()
    private val logger = HttpLoggingInterceptor.Logger { lines += it }

    private fun log(debugLogBodies: Boolean): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(buildDebugLogger(debugLogBodies, logger))
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("stub")
                    .body("""{"status":0}""".toResponseBody(JSON))
                    .build()
            }
            .build()
        val request = Request.Builder()
            .url("https://redaction.test.invalid/v1/thing")
            .header(HeaderInterceptor.HEADER_KEY_AUTHORIZATION, CREDENTIAL)
            .post(BODY.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().close()
        return lines.joinToString("\n")
    }

    // T50 — body logging on: the header value is replaced, never printed.
    @Test
    fun `the authorization header is redacted when bodies are logged`() {
        val log = log(debugLogBodies = true)

        assertTrue(log.contains("${HeaderInterceptor.HEADER_KEY_AUTHORIZATION}: ██"))
        assertFalse(log.contains(CREDENTIAL))
        // The body IS logged here, which is what makes T51's absence assertion meaningful.
        assertTrue(log.contains(BODY))
    }

    // T51 — bodies off (the fileshare client, whose body carries the token): headers only.
    @Test
    fun `body logging off omits the request body and still redacts the header`() {
        val log = log(debugLogBodies = false)

        assertTrue(log.contains("${HeaderInterceptor.HEADER_KEY_AUTHORIZATION}: ██"))
        assertFalse(log.contains(CREDENTIAL))
        assertFalse(log.contains(BODY))
        assertFalse(log.contains(BODY_SECRET))
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val CREDENTIAL = "Basic dGVzdDpwYXNzd29yZA=="
        const val BODY_SECRET = "body-token-secret"
        const val BODY = """{"token":"$BODY_SECRET"}"""
    }
}
