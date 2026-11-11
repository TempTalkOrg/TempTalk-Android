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

    
    @Test
    fun `the authorization header is redacted when bodies are logged`() {
        val log = log(debugLogBodies = true)

        assertTrue(log.contains("${HeaderInterceptor.HEADER_KEY_AUTHORIZATION}: ██"))
        assertFalse(log.contains(CREDENTIAL))
        
        assertTrue(log.contains(BODY))
    }

    
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
