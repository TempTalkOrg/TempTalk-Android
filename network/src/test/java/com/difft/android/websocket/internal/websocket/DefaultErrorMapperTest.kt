package com.difft.android.websocket.internal.websocket

import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.push.exceptions.CaptchaRequiredException
import com.difft.android.websocket.api.push.exceptions.DeprecatedVersionException
import com.difft.android.websocket.api.push.exceptions.ExpectationFailedException
import com.difft.android.websocket.api.push.exceptions.MalformedResponseException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.NotFoundException
import com.difft.android.websocket.api.push.exceptions.ProofRequiredException
import com.difft.android.websocket.api.push.exceptions.RateLimitException
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException
import com.difft.android.websocket.internal.push.DeviceLimitExceededException
import com.difft.android.websocket.internal.push.LockedException
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import com.difft.android.websocket.internal.push.exceptions.MismatchedDevicesException
import com.difft.android.websocket.internal.push.exceptions.StaleDevicesException
import org.junit.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefaultErrorMapperTest {

    private val mapper = DefaultErrorMapper.getDefault()

    // --- 401/403: AuthorizationFailedException ---

    @Test
    fun `401 returns AuthorizationFailedException`() {
        val error = mapper.parseError(401, "") { "" }
        assertIs<AuthorizationFailedException>(error)
        assertEquals(401, error.code)
    }

    @Test
    fun `403 returns AuthorizationFailedException`() {
        val error = mapper.parseError(403, "") { "" }
        assertIs<AuthorizationFailedException>(error)
        assertEquals(403, error.code)
    }

    // --- 402: CaptchaRequiredException ---

    @Test
    fun `402 returns CaptchaRequiredException`() {
        val error = mapper.parseError(402, "") { "" }
        assertIs<CaptchaRequiredException>(error)
    }

    // --- 404: AccountOfflineException / NotFoundException ---

    @Test
    fun `404 with account offline status 10105 returns AccountOfflineException`() {
        val body = """{"ver":1,"status":10105,"reason":"Account logged out","data":null}"""
        val error = mapper.parseError(404, body) { "" }
        assertIs<AccountOfflineException>(error)
        assertEquals(10105, error.status)
        assertEquals("Account logged out", error.reason)
    }

    @Test
    fun `404 with account offline status 10110 returns AccountOfflineException`() {
        val body = """{"ver":1,"status":10110,"reason":"Deactivated","data":null}"""
        val error = mapper.parseError(404, body) { "" }
        assertIs<AccountOfflineException>(error)
        assertEquals(10110, error.status)
    }

    @Test
    fun `404 without special status returns NotFoundException`() {
        val body = """{"ver":1,"status":200,"reason":"ok"}"""
        val error = mapper.parseError(404, body) { "" }
        assertIs<NotFoundException>(error)
    }

    @Test
    fun `404 with malformed body returns NotFoundException`() {
        val error = mapper.parseError(404, "not json") { "" }
        assertIs<NotFoundException>(error)
    }

    @Test
    fun `404 with empty body returns NotFoundException`() {
        val error = mapper.parseError(404, "") { "" }
        assertIs<NotFoundException>(error)
    }

    // --- 409: MismatchedDevicesException ---

    @Test
    fun `409 with valid body returns MismatchedDevicesException`() {
        val body = """{"missingDevices":[2,3],"extraDevices":[5]}"""
        val error = mapper.parseError(409, body) { "" }
        assertIs<MismatchedDevicesException>(error)
        assertEquals(listOf(2, 3), error.mismatchedDevices.missingDevices)
        assertEquals(listOf(5), error.mismatchedDevices.extraDevices)
    }

    @Test
    fun `409 with malformed body returns MalformedResponseException`() {
        val error = mapper.parseError(409, "not json") { "" }
        assertIs<MalformedResponseException>(error)
    }

    // --- 410: StaleDevicesException ---

    @Test
    fun `410 with valid body returns StaleDevicesException`() {
        val body = """{"staleDevices":[1,4]}"""
        val error = mapper.parseError(410, body) { "" }
        assertIs<StaleDevicesException>(error)
        assertEquals(listOf(1, 4), error.staleDevices.staleDevices)
    }

    @Test
    fun `410 with malformed body returns MalformedResponseException`() {
        val error = mapper.parseError(410, "not json") { "" }
        assertIs<MalformedResponseException>(error)
    }

    // --- 411: DeviceLimitExceededException ---

    @Test
    fun `411 with valid body returns DeviceLimitExceededException`() {
        val body = """{"current":3,"max":5}"""
        val error = mapper.parseError(411, body) { "" }
        assertIs<DeviceLimitExceededException>(error)
        assertEquals(3, error.deviceLimit.current)
        assertEquals(5, error.deviceLimit.max)
    }

    @Test
    fun `411 with malformed body returns MalformedResponseException`() {
        val error = mapper.parseError(411, "not json") { "" }
        assertIs<MalformedResponseException>(error)
    }

    // --- 413/429: RateLimitException ---

    @Test
    fun `413 with Retry-After header returns RateLimitException with millis`() {
        val error = mapper.parseError(413, "") { header ->
            if (header == "Retry-After") "60" else ""
        }
        assertIs<RateLimitException>(error)
        assertEquals(Optional.of(60_000L), error.retryAfterMilliseconds)
    }

    @Test
    fun `429 with Retry-After header returns RateLimitException with millis`() {
        val error = mapper.parseError(429, "") { header ->
            if (header == "Retry-After") "120" else ""
        }
        assertIs<RateLimitException>(error)
        assertEquals(Optional.of(120_000L), error.retryAfterMilliseconds)
    }

    @Test
    fun `413 without Retry-After returns RateLimitException with empty optional`() {
        val error = mapper.parseError(413, "") { "" }
        assertIs<RateLimitException>(error)
        assertEquals(Optional.empty<Long>(), error.retryAfterMilliseconds)
    }

    // --- 417: ExpectationFailedException ---

    @Test
    fun `417 returns ExpectationFailedException`() {
        val error = mapper.parseError(417, "") { "" }
        assertIs<ExpectationFailedException>(error)
    }

    // --- 423: LockedException ---

    @Test
    fun `423 with credentials returns LockedException`() {
        val body = """{"length":7,"timeRemaining":12345,"backupCredentials":{"username":"u","password":"p"}}"""
        val error = mapper.parseError(423, body) { "" }
        assertIs<LockedException>(error)
        assertEquals(7, error.length)
        assertEquals(12345L, error.timeRemaining)
        assertNotNull(error.basicStorageCredentials)
    }

    @Test
    fun `423 without credentials returns LockedException with null credentials`() {
        val body = """{"length":7,"timeRemaining":12345}"""
        val error = mapper.parseError(423, body) { "" }
        assertIs<LockedException>(error)
        assertNull(error.basicStorageCredentials)
    }

    @Test
    fun `423 with malformed body returns MalformedResponseException`() {
        val error = mapper.parseError(423, "not json") { "" }
        assertIs<MalformedResponseException>(error)
    }

    // --- 428: ProofRequiredException ---

    @Test
    fun `428 with valid body returns ProofRequiredException`() {
        val body = """{"token":"abc","options":["recaptcha","pushChallenge"]}"""
        val error = mapper.parseError(428, body) { "" }
        assertIs<ProofRequiredException>(error)
        assertEquals("abc", error.token)
        assertEquals(
            setOf(ProofRequiredException.Option.RECAPTCHA, ProofRequiredException.Option.PUSH_CHALLENGE),
            error.options
        )
    }

    @Test
    fun `428 with Retry-After sets retryAfterSeconds`() {
        val body = """{"token":"abc","options":[]}"""
        val error = mapper.parseError(428, body) { header ->
            if (header == "Retry-After") "30" else ""
        }
        assertIs<ProofRequiredException>(error)
        assertEquals(30L, error.retryAfterSeconds)
    }

    @Test
    fun `428 with malformed body returns MalformedResponseException`() {
        val error = mapper.parseError(428, "not json") { "" }
        assertIs<MalformedResponseException>(error)
    }

    // --- 499: DeprecatedVersionException ---

    @Test
    fun `499 returns DeprecatedVersionException`() {
        val error = mapper.parseError(499, "") { "" }
        assertIs<DeprecatedVersionException>(error)
    }

    // --- 508: ServerRejectedException ---

    @Test
    fun `508 returns ServerRejectedException`() {
        val error = mapper.parseError(508, "") { "" }
        assertIs<ServerRejectedException>(error)
    }

    // --- Success status codes return null ---

    @Test
    fun `200 returns null`() {
        val error = mapper.parseError(200, "") { "" }
        assertNull(error)
    }

    @Test
    fun `202 returns null`() {
        val error = mapper.parseError(202, "") { "" }
        assertNull(error)
    }

    @Test
    fun `204 returns null`() {
        val error = mapper.parseError(204, "") { "" }
        assertNull(error)
    }

    // --- Unknown error status returns NonSuccessfulResponseCodeException ---

    @Test
    fun `500 returns NonSuccessfulResponseCodeException`() {
        val error = mapper.parseError(500, "") { "" }
        assertIs<NonSuccessfulResponseCodeException>(error)
        assertEquals(500, error.code)
    }

    @Test
    fun `503 returns NonSuccessfulResponseCodeException`() {
        val error = mapper.parseError(503, "") { "" }
        assertIs<NonSuccessfulResponseCodeException>(error)
        assertEquals(503, error.code)
    }

    // --- Builder / extend ---

    @Test
    fun `extend creates Builder`() {
        val builder = DefaultErrorMapper.extend()
        assertNotNull(builder)
        val errorMapper = builder.build()
        assertNotNull(errorMapper)
    }

    // --- Custom error mappers override defaults ---

    @Test
    fun `custom 409 mapper is called instead of default`() {
        val customException = RuntimeException("Custom 409 handler")
        val customMapper = DefaultErrorMapper.extend()
            .withCustom(409, ErrorMapper { _, _, _ -> customException })
            .build()

        val body = """{"missingDevices":[2,3],"extraDevices":[5]}"""
        val error = customMapper.parseError(409, body) { "" }
        assertEquals(customException, error)
    }

    @Test
    fun `custom 410 mapper is called instead of default`() {
        val customException = RuntimeException("Custom 410 handler")
        val customMapper = DefaultErrorMapper.extend()
            .withCustom(410, ErrorMapper { _, _, _ -> customException })
            .build()

        val body = """{"staleDevices":[1,4]}"""
        val error = customMapper.parseError(410, body) { "" }
        assertEquals(customException, error)
    }

    @Test
    fun `custom 404 mapper is called instead of default`() {
        val customException = NotFoundException("Custom not found")
        val customMapper = DefaultErrorMapper.extend()
            .withCustom(404, ErrorMapper { _, _, _ -> customException })
            .build()

        val error = customMapper.parseError(404, "") { "" }
        assertEquals(customException, error)
    }

    @Test
    fun `custom mapper MalformedResponseException is returned as-is`() {
        val customMapper = DefaultErrorMapper.extend()
            .withCustom(409, ErrorMapper { _, _, _ ->
                throw MalformedResponseException("bad body")
            })
            .build()

        val error = customMapper.parseError(409, "bad") { "" }
        assertIs<MalformedResponseException>(error)
    }

    @Test
    fun `non-overridden status still uses default when custom mappers exist`() {
        val customMapper = DefaultErrorMapper.extend()
            .withCustom(409, ErrorMapper { _, _, _ -> RuntimeException("custom") })
            .build()

        // 401 should still use default behavior
        val error = customMapper.parseError(401, "") { "" }
        assertIs<AuthorizationFailedException>(error)
        assertEquals(401, error.code)
    }

    @Test
    fun `custom mapper receives body and header function`() {
        var receivedBody = ""
        var receivedHeader = ""
        val customMapper = DefaultErrorMapper.extend()
            .withCustom(409, ErrorMapper { status, body, getHeader ->
                receivedBody = body
                receivedHeader = getHeader.apply("X-Test")
                RuntimeException("captured")
            })
            .build()

        customMapper.parseError(409, "test-body") { key ->
            if (key == "X-Test") "header-value" else ""
        }

        assertEquals("test-body", receivedBody)
        assertEquals("header-value", receivedHeader)
    }
}
