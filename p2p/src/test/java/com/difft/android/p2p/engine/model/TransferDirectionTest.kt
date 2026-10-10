package com.difft.android.p2p.engine.model

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the `dir` contract (统一由手机扫码 §4.1): absent or unknown means upload — the behaviour every
 * scanner had before the field existed — and a payload carrying `dir` still round-trips through the
 * same codec, so an older scanner decodes it and simply ignores the field. `download` now selects
 * reverse pairing, but the wire value is unchanged so codes minted by earlier releases still decode.
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
    fun `dir download selects the reverse-pairing direction`() {
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
}
