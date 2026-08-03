package com.difft.android.network

import com.difft.android.base.utils.time.ServerTimeProvider
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [ServerTimeCaptureConverterFactory]: verifies the read-only time-capture hook fires
 * only on a positive-serverTimestamp [BaseResponse] and that all other conversions delegate unchanged.
 */
class ServerTimeCaptureConverterFactoryTest {

    private val delegate = mockk<Converter.Factory>()
    private val factory = ServerTimeCaptureConverterFactory(delegate)

    private val type: Type = BaseResponse::class.java
    private val annotations = emptyArray<Annotation>()
    private val retrofit = mockk<Retrofit>(relaxed = true)

    @Before
    fun setUp() {
        // Deterministic clocks; anchor cleared so isAnchored() reflects only what this test does.
        ServerTimeProvider.resetForTest(wallClock = { 100_000L }, elapsedClock = { 5_000L })
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun wrappedConverter(inner: Converter<ResponseBody, Any?>): Converter<ResponseBody, *> {
        every { delegate.responseBodyConverter(any(), any(), any()) } returns inner
        return factory.responseBodyConverter(type, annotations, retrofit)!!
    }

    private fun baseResponse(serverTimestamp: Long?): BaseResponse<Any> =
        BaseResponse(ver = 1, status = 0, reason = null, data = null, serverTimestamp = serverTimestamp)

    @Test
    fun `BaseResponse with positive serverTimestamp anchors provider`() {
        val serverTs = 1_700_000_000_000L
        val inner = mockk<Converter<ResponseBody, Any?>>()
        every { inner.convert(any()) } returns baseResponse(serverTs)

        val result = wrappedConverter(inner).convert(mockk())

        assertTrue(ServerTimeProvider.isAnchored())
        // elapsedClock == anchorElapsed at update time, so nowMillis collapses to serverNow.
        assertEquals(serverTs, ServerTimeProvider.nowMillis())
        // The delegate's payload is returned unchanged (capture is a read-only side effect).
        assertEquals(serverTs, (result as BaseResponse<*>).serverTimestamp)
    }

    @Test
    fun `null serverTimestamp does not anchor`() {
        val inner = mockk<Converter<ResponseBody, Any?>>()
        every { inner.convert(any()) } returns baseResponse(null)

        wrappedConverter(inner).convert(mockk())

        assertFalse(ServerTimeProvider.isAnchored())
    }

    @Test
    fun `non-positive serverTimestamp does not anchor`() {
        val inner = mockk<Converter<ResponseBody, Any?>>()
        every { inner.convert(any()) } returns baseResponse(0L)

        wrappedConverter(inner).convert(mockk())

        assertFalse(ServerTimeProvider.isAnchored())
    }

    @Test
    fun `non-BaseResponse payload passes through and does not anchor`() {
        val payload = "plain-string-payload"
        val inner = mockk<Converter<ResponseBody, Any?>>()
        every { inner.convert(any()) } returns payload

        val result = wrappedConverter(inner).convert(mockk())

        assertEquals(payload, result)
        assertFalse(ServerTimeProvider.isAnchored())
    }

    @Test
    fun `null delegate converter yields null passthrough`() {
        every { delegate.responseBodyConverter(any(), any(), any()) } returns null

        assertNull(factory.responseBodyConverter(type, annotations, retrofit))
    }

    @Test
    fun `requestBodyConverter delegates untouched`() {
        val reqConverter = mockk<Converter<*, RequestBody>>()
        val methodAnnotations = emptyArray<Annotation>()
        every {
            delegate.requestBodyConverter(any(), any(), any(), any())
        } returns reqConverter

        val result = factory.requestBodyConverter(type, annotations, methodAnnotations, retrofit)

        assertSame(reqConverter, result)
        verify { delegate.requestBodyConverter(type, annotations, methodAnnotations, retrofit) }
    }

    @Test
    fun `stringConverter delegates untouched`() {
        val stringConverter = mockk<Converter<*, String>>()
        every { delegate.stringConverter(any(), any(), any()) } returns stringConverter

        val result = factory.stringConverter(type, annotations, retrofit)

        assertSame(stringConverter, result)
        verify { delegate.stringConverter(type, annotations, retrofit) }
    }
}
