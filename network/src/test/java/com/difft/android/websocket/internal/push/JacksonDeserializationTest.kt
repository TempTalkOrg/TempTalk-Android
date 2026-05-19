package com.difft.android.websocket.internal.push

import com.difft.android.websocket.internal.util.JsonUtil
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verify all Kotlin-converted model classes deserialize correctly via JsonUtil.fromJson.
 * These tests guard against regressions from Java-to-Kotlin data class conversions.
 */
class JacksonDeserializationTest {

    // --- MismatchedDevices ---

    @Test
    fun `MismatchedDevices deserializes with populated lists`() {
        val json = """{"missingDevices":[1,2],"extraDevices":[3]}"""
        val result = JsonUtil.fromJson(json, MismatchedDevices::class.java)
        assertNotNull(result)
        assertEquals(listOf(1, 2), result.missingDevices)
        assertEquals(listOf(3), result.extraDevices)
    }

    @Test
    fun `MismatchedDevices deserializes with empty lists`() {
        val json = """{"missingDevices":[],"extraDevices":[]}"""
        val result = JsonUtil.fromJson(json, MismatchedDevices::class.java)
        assertNotNull(result)
        assertEquals(emptyList(), result.missingDevices)
        assertEquals(emptyList(), result.extraDevices)
    }

    @Test
    fun `MismatchedDevices deserializes with missing fields uses defaults`() {
        val json = """{}"""
        val result = JsonUtil.fromJson(json, MismatchedDevices::class.java)
        assertNotNull(result)
        assertEquals(emptyList(), result.missingDevices)
        assertEquals(emptyList(), result.extraDevices)
    }

    // --- StaleDevices ---

    @Test
    fun `StaleDevices deserializes with populated list`() {
        val json = """{"staleDevices":[1,2]}"""
        val result = JsonUtil.fromJson(json, StaleDevices::class.java)
        assertNotNull(result)
        assertEquals(listOf(1, 2), result.staleDevices)
    }

    @Test
    fun `StaleDevices deserializes with empty list`() {
        val json = """{"staleDevices":[]}"""
        val result = JsonUtil.fromJson(json, StaleDevices::class.java)
        assertNotNull(result)
        assertEquals(emptyList(), result.staleDevices)
    }

    @Test
    fun `StaleDevices deserializes with missing field uses default`() {
        val json = """{}"""
        val result = JsonUtil.fromJson(json, StaleDevices::class.java)
        assertNotNull(result)
        assertEquals(emptyList(), result.staleDevices)
    }

    // --- SocketResponse ---

    @Test
    fun `SocketResponse deserializes with all fields`() {
        val json = """{"ver":1,"status":10105,"reason":"offline"}"""
        val result = JsonUtil.fromJson(json, SocketResponse::class.java)
        assertNotNull(result)
        assertEquals(1, result.ver)
        assertEquals(10105, result.status)
        assertEquals("offline", result.reason)
    }

    @Test
    fun `SocketResponse deserializes with account offline status 10110`() {
        val json = """{"ver":1,"status":10110,"reason":"Deactivated"}"""
        val result = JsonUtil.fromJson(json, SocketResponse::class.java)
        assertNotNull(result)
        assertEquals(10110, result.status)
        assertEquals("Deactivated", result.reason)
    }

    @Test
    fun `SocketResponse deserializes with extra fields ignored`() {
        val json = """{"ver":1,"status":200,"reason":"ok","data":null,"extra":"field"}"""
        val result = JsonUtil.fromJson(json, SocketResponse::class.java)
        assertNotNull(result)
        assertEquals(200, result.status)
    }

    @Test
    fun `SocketResponse deserializes with missing fields uses defaults`() {
        val json = """{}"""
        val result = JsonUtil.fromJson(json, SocketResponse::class.java)
        assertNotNull(result)
        assertEquals(0, result.ver)
        assertEquals(0, result.status)
        assertEquals("", result.reason)
    }

    // --- AuthCredentials ---

    @Test
    fun `AuthCredentials deserializes with username and password`() {
        val json = """{"username":"user","password":"pass"}"""
        val result = JsonUtil.fromJson(json, AuthCredentials::class.java)
        assertNotNull(result)
        assertEquals("user", result.username)
        assertEquals("pass", result.password)
    }

    @Test
    fun `AuthCredentials asBasic returns valid basic auth string`() {
        val json = """{"username":"user","password":"pass"}"""
        val result = JsonUtil.fromJson(json, AuthCredentials::class.java)
        assertNotNull(result)
        val basic = result.asBasic()
        assertNotNull(basic)
        // Basic auth format: "Basic <base64(user:pass)>"
        assert(basic.startsWith("Basic ")) { "Expected Basic auth prefix, got: $basic" }
    }

    @Test
    fun `AuthCredentials deserializes with missing fields uses defaults`() {
        val json = """{}"""
        val result = JsonUtil.fromJson(json, AuthCredentials::class.java)
        assertNotNull(result)
        assertEquals("", result.username)
        assertEquals("", result.password)
    }

    // --- RegistrationLockFailure ---

    @Test
    fun `RegistrationLockFailure deserializes with all fields`() {
        val json = """{"length":10,"timeRemaining":5000,"backupCredentials":{"username":"u","password":"p"}}"""
        val result = JsonUtil.fromJson(json, RegistrationLockFailure::class.java)
        assertNotNull(result)
        assertEquals(10, result.length)
        assertEquals(5000L, result.timeRemaining)
        assertNotNull(result.backupCredentials)
        assertEquals("u", result.backupCredentials!!.username)
        assertEquals("p", result.backupCredentials!!.password)
    }

    @Test
    fun `RegistrationLockFailure deserializes without credentials`() {
        val json = """{"length":7,"timeRemaining":12345}"""
        val result = JsonUtil.fromJson(json, RegistrationLockFailure::class.java)
        assertNotNull(result)
        assertEquals(7, result.length)
        assertEquals(12345L, result.timeRemaining)
        assertEquals(null, result.backupCredentials)
    }

    @Test
    fun `RegistrationLockFailure deserializes with missing fields uses defaults`() {
        val json = """{}"""
        val result = JsonUtil.fromJson(json, RegistrationLockFailure::class.java)
        assertNotNull(result)
        assertEquals(0, result.length)
        assertEquals(0L, result.timeRemaining)
        assertEquals(null, result.backupCredentials)
    }

    // --- NewSendMessageResponse ---

    @Test
    fun `NewSendMessageResponse deserializes with all fields`() {
        val json = """{"ver":1,"status":200,"reason":"ok","data":{"needsSync":true,"sequenceId":42}}"""
        val result = JsonUtil.fromJson(json, NewSendMessageResponse::class.java)
        assertNotNull(result)
        assertEquals(1, result.ver)
        assertEquals(200, result.status)
        assertEquals("ok", result.reason)
        assertNotNull(result.data)
        assertEquals(true, result.data.isNeedsSync)
        assertEquals(42L, result.data.sequenceId)
    }

    @Test
    fun `NewSendMessageResponse deserializes with empty data`() {
        val json = """{"ver":1,"status":200,"reason":"ok"}"""
        val result = JsonUtil.fromJson(json, NewSendMessageResponse::class.java)
        assertNotNull(result)
        assertEquals(null, result.data)
    }

    @Test
    fun `NewSendMessageResponse deserializes from empty object`() {
        val json = """{}"""
        val result = JsonUtil.fromJson(json, NewSendMessageResponse::class.java)
        assertNotNull(result)
        assertEquals(0, result.ver)
        assertEquals(0, result.status)
    }
}
