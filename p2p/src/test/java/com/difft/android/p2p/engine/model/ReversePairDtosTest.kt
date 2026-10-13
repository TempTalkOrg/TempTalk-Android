package com.difft.android.p2p.engine.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire conformance for reverse pairing. The peer implementations are written against the field names
 * in the cross-platform plan, so a rename here is a silent interop break; and `pairToken` must stay
 * off a phone-to-phone `prepare-upload` so that path is byte-identical to what shipped.
 */
class ReversePairDtosTest {

    private val request = ReversePairRequest(
        device = ReversePairDevice(alias = "Pixel 8", appVersion = "2.4.5", fingerprint = "ab".repeat(32)),
        listen = ReversePairListen(addresses = listOf("192.168.1.23"), port = 53317),
        pairToken = "dG9rZW4",
        precheck = ReversePairPrecheck(env = "online", formatVersion = "1.0", account = "u_a"),
    )

    @Test
    fun `the request carries exactly the agreed top-level keys`() {
        val json = P2pJson.parseToJsonElement(P2pJson.encodeToString(request)).jsonObject

        assertEquals(setOf("protocol", "device", "listen", "pairToken", "precheck"), json.keys)
        assertEquals(
            setOf("alias", "platform", "appVersion", "fingerprint"),
            json.getValue("device").jsonObject.keys,
        )
        assertEquals(setOf("addresses", "port"), json.getValue("listen").jsonObject.keys)
        assertEquals(setOf("env", "formatVersion", "account"), json.getValue("precheck").jsonObject.keys)
    }

    @Test
    fun `the request declares protocol 1 and the android platform`() {
        val encoded = P2pJson.encodeToString(request)

        assertTrue(encoded.contains("\"protocol\":$REVERSE_PAIR_PROTOCOL"))
        assertTrue(encoded.contains("\"platform\":\"$PLATFORM_ANDROID\""))
    }

    @Test
    fun `a response without the optional peer or deadline still decodes`() {
        val full = P2pJson.decodeFromString<ReversePairResponse>(
            """{"sessionId":"s1","device":{"alias":"MacBook","platform":"desktop","appVersion":"1.2.0"},"connectWithinMs":30000}""",
        )
        assertEquals("s1", full.sessionId)
        assertEquals("MacBook", full.device?.alias)
        assertEquals(30_000L, full.connectWithinMs)

        val bare = P2pJson.decodeFromString<ReversePairResponse>("""{"sessionId":"s2","extra":true}""")
        assertEquals("s2", bare.sessionId)
        assertNull(bare.device)
        assertNull(bare.connectWithinMs)
    }

    @Test
    fun `prepare-upload omits pairToken when there is no pairing, and carries it when there is`() {
        val info = DeviceInfo(alias = "phone", fingerprint = "fp")
        val files = mapOf(
            "f1" to FileMetadata(id = "f1", fileName = "c.ttmig", size = 1, fileType = "application/octet-stream"),
        )

        val plain = P2pJson.encodeToString(PrepareUploadRequest(info = info, files = files))
        assertFalse("phone-to-phone must stay byte-identical", plain.contains("pairToken"))

        val paired = P2pJson.encodeToString(PrepareUploadRequest(info = info, files = files, pairToken = "dG9rZW4"))
        assertTrue(paired.contains("\"pairToken\":\"dG9rZW4\""))
        assertEquals("dG9rZW4", P2pJson.decodeFromString<PrepareUploadRequest>(paired).pairToken)
    }
}
