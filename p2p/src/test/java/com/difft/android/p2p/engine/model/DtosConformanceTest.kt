package com.difft.android.p2p.engine.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-W1 / T-W2 — wire-compat conformance (design §5, §13). The real LocalSend app is the
 * conformance oracle: every JSON key must match protocol-spec.md §A byte-for-byte. These tests
 * fail in CI if a `@SerialName` is typo'd or a key is added/dropped — BEFORE it reaches a device.
 */
class DtosConformanceTest {

    private val parser = Json { ignoreUnknownKeys = true }

    @Test
    fun `T-W1 DeviceInfo emits exactly the §A2 key set`() {
        val info = DeviceInfo(alias = "Nice Orange", fingerprint = "abc123", deviceModel = "Samsung")
        val obj = parser.parseToJsonElement(P2pJson.encodeToString(info)) as JsonObject
        // explicitNulls=false omits null deviceModel? No — deviceModel is set here, so present.
        assertEquals(
            setOf("alias", "version", "deviceModel", "deviceType", "fingerprint", "port", "protocol", "download", "announce"),
            obj.keys,
        )
        assertEquals("\"2.1\"", obj["version"].toString())
        assertEquals("\"https\"", obj["protocol"].toString())
        assertEquals("53317", obj["port"].toString())
    }

    @Test
    fun `T-W1 DeviceInfo omits null optionals (explicitNulls=false)`() {
        val info = DeviceInfo(alias = "A", fingerprint = "fp") // deviceModel = null
        val obj = parser.parseToJsonElement(P2pJson.encodeToString(info)) as JsonObject
        assertTrue("deviceModel must be omitted when null", "deviceModel" !in obj.keys)
    }

    @Test
    fun `T-W1 FileMetadata emits the §A5 key set and omits null optionals`() {
        val meta = FileMetadata(id = "f1", fileName = "a.png", size = 324242, fileType = "image/jpeg", sha256 = "deadbeef")
        val obj = parser.parseToJsonElement(P2pJson.encodeToString(meta)) as JsonObject
        assertEquals(setOf("id", "fileName", "size", "fileType", "sha256"), obj.keys)
        assertTrue("preview omitted when null", "preview" !in obj.keys)
        assertTrue("metadata omitted when null", "metadata" !in obj.keys)
    }

    @Test
    fun `T-W1 PrepareUploadResponse maps fileId to token`() {
        val resp = PrepareUploadResponse(sessionId = "s1", files = mapOf("f1" to "tok1"))
        val obj = parser.parseToJsonElement(P2pJson.encodeToString(resp)) as JsonObject
        assertEquals(setOf("sessionId", "files"), obj.keys)
        assertEquals("\"s1\"", obj["sessionId"].toString())
    }

    @Test
    fun `T-W1 QrPayload emits the §C2 key set and omits the null transferKey`() {
        val qr = QrPayload(ip = "192.168.1.23", fp = "ff", pin = "123456", alias = "Phone")
        val obj = parser.parseToJsonElement(P2pJson.encodeToString(qr)) as JsonObject
        // transferKey is null here (plain file-transfer QR) → omitted by explicitNulls=false, so the
        // file demo's QR is byte-identical to before the migration extension was added.
        assertEquals(setOf("v", "ip", "port", "fp", "pin", "alias"), obj.keys)
        assertTrue("transferKey must be omitted when null", "transferKey" !in obj.keys)
        assertEquals("1", obj["v"].toString())
    }

    @Test
    fun `QrPayload carries the migration transferKey when set and round-trips`() {
        val qr = QrPayload(ip = "10.0.0.5", fp = "aa", pin = "654321", alias = "New", transferKey = "Zm9vYmFy")
        val json = P2pJson.encodeToString(qr)
        val obj = parser.parseToJsonElement(json) as JsonObject
        assertEquals(setOf("v", "ip", "port", "fp", "pin", "alias", "transferKey"), obj.keys)
        assertEquals("\"Zm9vYmFy\"", obj["transferKey"].toString())
        assertEquals("Zm9vYmFy", P2pJson.decodeFromString<QrPayload>(json).transferKey)
    }

    @Test
    fun `I4 PrepareUploadRequest omits the null migration handshake (oracle byte-compat)`() {
        // A generic file-transfer / real-LocalSend prepare-upload carries NO handshake → the `migration`
        // key must be omitted (explicitNulls=false), so the request is byte-identical to before I4.
        val info = DeviceInfo(alias = "S", fingerprint = "fp")
        val req = PrepareUploadRequest(info, mapOf("f1" to FileMetadata("f1", "a.bin", 3, "application/octet-stream")))
        val obj = parser.parseToJsonElement(P2pJson.encodeToString(req)) as JsonObject
        assertEquals(setOf("info", "files"), obj.keys)
        assertTrue("migration must be omitted when null", "migration" !in obj.keys)
    }

    @Test
    fun `I4 PrepareUploadRequest carries the migration handshake when set and round-trips`() {
        val info = DeviceInfo(alias = "S", fingerprint = "fp")
        val hs = MigrationHandshake(
            sourceEnvironment = "test", sourceAccount = "u_a", sourceAppVersion = "1.2.3", sourceFormatVersion = "1.0",
        )
        val req = PrepareUploadRequest(
            info, mapOf("f1" to FileMetadata("f1", "a.bin", 3, "application/octet-stream")), migration = hs,
        )
        val json = P2pJson.encodeToString(req)
        val obj = parser.parseToJsonElement(json) as JsonObject
        assertTrue("migration present when set", "migration" in obj.keys)
        val back = P2pJson.decodeFromString<PrepareUploadRequest>(json).migration!!
        assertEquals("test", back.sourceEnvironment)
        assertEquals("u_a", back.sourceAccount)
        assertEquals("1.2.3", back.sourceAppVersion)
        assertEquals("1.0", back.sourceFormatVersion)
    }

    @Test
    fun `I4 QrPayload omits the null migration identity fields (file QR byte-compat)`() {
        val qr = QrPayload(ip = "192.168.1.23", fp = "ff", pin = "123456", alias = "Phone")
        val obj = parser.parseToJsonElement(P2pJson.encodeToString(qr)) as JsonObject
        for (k in listOf("env", "account", "appVersion", "formatVersion")) {
            assertTrue("$k must be omitted when null", k !in obj.keys)
        }
    }

    @Test
    fun `I4 QrPayload carries the migration identity when set and round-trips`() {
        val qr = QrPayload(
            ip = "10.0.0.5", fp = "aa", pin = "654321", alias = "New", transferKey = "Zm9v",
            environment = "prod", account = "u_x", appVersion = "2.0.0", formatVersion = "1.0",
        )
        val back = P2pJson.decodeFromString<QrPayload>(P2pJson.encodeToString(qr))
        assertEquals("prod", back.environment)
        assertEquals("u_x", back.account)
        assertEquals("2.0.0", back.appVersion)
        assertEquals("1.0", back.formatVersion)
    }

    @Test
    fun `T-W2 parses the literal §A5 prepare-upload body`() {
        val body = """
            { "info": { "alias":"Nice Orange", "version":"2.0", "deviceModel":"Samsung",
              "deviceType":"mobile", "fingerprint":"id-xyz", "port":53317,
              "protocol":"https", "download":true, "announce":true },
              "files": { "some-id": {
                  "id":"some-id", "fileName":"a.png", "size":324242,
                  "fileType":"image/jpeg", "sha256":"hex-digest",
                  "metadata": { "modified":"2021-01-01T00:00:00Z", "accessed":"2021-01-01T00:00:00Z" } } } }
        """.trimIndent()
        val req = P2pJson.decodeFromString<PrepareUploadRequest>(body)
        assertEquals("Nice Orange", req.info.alias)
        assertEquals("mobile", req.info.deviceType)
        assertEquals(1, req.files.size)
        val file = req.files.getValue("some-id")
        assertEquals("a.png", file.fileName)
        assertEquals(324242L, file.size)
        assertEquals("hex-digest", file.sha256)
        assertEquals("2021-01-01T00:00:00Z", file.metadata?.modified)
    }

    @Test
    fun `T-W2 ignores unknown forward-compat keys`() {
        val body = """{ "alias":"X", "fingerprint":"fp", "futureField":"ignored", "nested":{"a":1} }"""
        val info = P2pJson.decodeFromString<DeviceInfo>(body)
        assertEquals("X", info.alias)
        assertEquals("fp", info.fingerprint)
    }

    @Test
    fun `T-W1 register-reply and info DeviceInfo body never leaks the transient lastSeenIp`() {
        // The register-reply (§A.3) POST body and the GET /info 200 body are both
        // P2pJson.encodeToString(DeviceInfo). lastSeenIp is a transport-observed @Transient field;
        // it MUST NOT serialize, or it would (a) break the §A.2 key set and (b) leak an IP on the wire.
        val info = DeviceInfo(alias = "Me", fingerprint = "fp", port = 49152, lastSeenIp = "192.168.1.9")
        val json = P2pJson.encodeToString(info)
        val obj = parser.parseToJsonElement(json) as JsonObject
        assertTrue("lastSeenIp must never appear on the wire", "lastSeenIp" !in obj.keys)
        // deviceModel is null here → omitted by explicitNulls=false; the rest are the §A.2 keys.
        assertEquals(
            setOf("alias", "version", "deviceType", "fingerprint", "port", "protocol", "download", "announce"),
            obj.keys,
        )
        assertEquals("49152", obj["port"].toString())
        // And it round-trips back with lastSeenIp defaulting to null (the wire never carried it).
        val back = P2pJson.decodeFromString<DeviceInfo>(json)
        assertEquals("Me", back.alias)
        assertNull(back.lastSeenIp)
    }

    @Test
    fun `T-W2 parses prepare-upload response`() {
        val resp = P2pJson.decodeFromString<PrepareUploadResponse>(
            """{ "sessionId":"sess-1", "files": { "f1":"token-a", "f2":"token-b" } }""",
        )
        assertEquals("sess-1", resp.sessionId)
        assertEquals("token-a", resp.files["f1"])
        assertNull(resp.files["missing"])
    }
}
