package com.difft.android.p2p.engine.model

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the `dir` contract (统一由手机扫码 §4.1): absent or unknown means upload — the behaviour every
 * scanner had before the field existed — and a payload carrying `dir` still round-trips through the
 * same codec, so an older scanner decodes it and simply ignores the field.
 */
class TransferDirectionTest {

    private val base = """{"v":1,"ip":"192.168.1.23","port":47821,"fp":"AA:BB","pin":"482913","alias":"TempTalk Desktop","transferKey":"a2V5"}"""

    @Test
    fun `a payload without dir is an upload`() {
        val payload = P2pJson.decodeFromString<QrPayload>(base)
        assertNull(payload.dir)
        assertEquals(TransferDirection.UPLOAD, payload.direction)
    }

    @Test
    fun `dir download selects the pull direction`() {
        val payload = P2pJson.decodeFromString<QrPayload>(base.dropLast(1) + ""","dir":"download"}""")
        assertEquals("download", payload.dir)
        assertEquals(TransferDirection.DOWNLOAD, payload.direction)
    }

    @Test
    fun `an unrecognised dir falls back to upload`() {
        assertEquals(TransferDirection.UPLOAD, TransferDirection.of("sideways"))
        assertEquals(TransferDirection.UPLOAD, TransferDirection.of(""))
        assertEquals(TransferDirection.UPLOAD, TransferDirection.of(null))
        assertEquals(TransferDirection.DOWNLOAD, TransferDirection.of(" Download "))
    }

    @Test
    fun `dir is omitted from an encoded payload that did not set it`() {
        val payload = P2pJson.decodeFromString<QrPayload>(base)
        val encoded = P2pJson.encodeToString(payload)
        assertEquals(false, encoded.contains("\"dir\""))
    }

    @Test
    fun `prepare-download response tolerates upstream extras and missing tokens`() {
        val json = """{"info":{"alias":"x","fingerprint":"f"},"sessionId":"s1","files":{"f1":{"id":"f1","fileName":"c.ttmig","size":123,"fileType":"application/octet-stream"}},"tokens":{"f1":"t1"},"migration":{"env":"e","account":"a","appVersion":"1","formatVersion":"1.0"}}"""
        val resp = P2pJson.decodeFromString<PrepareDownloadResponse>(json)
        assertEquals("s1", resp.sessionId)
        assertEquals(123L, resp.files.getValue("f1").size)
        assertEquals("t1", resp.tokens["f1"])
        assertEquals("a", resp.migration?.sourceAccount)
        val bare = P2pJson.decodeFromString<PrepareDownloadResponse>("""{"sessionId":"s2"}""")
        assertEquals(0, bare.files.size)
        assertNull(bare.migration)
    }
}
