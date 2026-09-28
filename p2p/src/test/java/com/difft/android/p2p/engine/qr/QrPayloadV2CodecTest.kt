package com.difft.android.p2p.engine.qr

import com.difft.android.base.migration.Base45
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.TransferDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The v2 layout against the cross-platform vectors (§2.6), and every reject the three platforms
 * agreed on. A vector mismatch here means the other two platforms will not read our codes.
 */
class QrPayloadV2CodecTest {

    private val fp = QrPayloadV2Vectors.FP_HEX
    private val key = QrPayloadV2Vectors.KEY_BASE64

    private val phoneReceive = QrPayload(
        ip = "192.168.0.106", ips = listOf("192.168.0.106", "192.168.43.1"), fp = fp, pin = QrPayloadV2Vectors.PIN,
        alias = "Pixel 8 Pro", transferKey = key, environment = QrPayloadV2Codec.Environment.PROD,
        account = QrPayloadV2Vectors.ACCOUNT, appVersion = "2.4.4", formatVersion = "1.0",
    )
    private val desktopLogin = QrPayload(ip = "10.0.0.5", fp = fp, pin = QrPayloadV2Vectors.PIN, alias = "Nate's MacBook", transferKey = key)
    private val desktopExport = phoneReceive.copy(alias = "Nate's MacBook", dir = TransferDirection.DOWNLOAD.wire)

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun `vector A - phone receive code`() {
        val bytes = QrPayloadV2Codec.encode(phoneReceive)
        assertEquals(QrPayloadV2Vectors.A_HEX, bytes.toHex())
        assertEquals(QrPayloadV2Vectors.A_BASE45, Base45.encode(bytes))
        assertEquals(phoneReceive.copy(v = 2), QrPayloadV2Codec.decode(bytes))
    }

    @Test
    fun `vector B - Desktop login link inner payload, no precheck, alias kept`() {
        val bytes = QrPayloadV2Codec.encode(desktopLogin)
        assertEquals(QrPayloadV2Vectors.B_HEX, bytes.toHex())
        val decoded = QrPayloadV2Codec.decode(bytes)!!
        assertEquals("Nate's MacBook", decoded.alias)
        assertNull(decoded.environment)
        assertNull(decoded.account)
        assertEquals(listOf("10.0.0.5"), decoded.ips)
    }

    @Test
    fun `vector C - Desktop export code carries the download direction`() {
        val bytes = QrPayloadV2Codec.encode(desktopExport)
        assertEquals(QrPayloadV2Vectors.C_HEX, bytes.toHex())
        assertEquals(0x07, bytes[1].toInt())
        assertEquals(TransferDirection.DOWNLOAD, TransferDirection.of(QrPayloadV2Codec.decode(bytes)!!.dir))
    }

    @Test
    fun `a partial identity is dropped whole, and the test environment maps to 1`() {
        val noVersion = phoneReceive.copy(appVersion = null)
        val decoded = QrPayloadV2Codec.decode(QrPayloadV2Codec.encode(noVersion))!!
        assertNull(decoded.environment)
        assertNull(decoded.account)
        val test = phoneReceive.copy(environment = QrPayloadV2Codec.Environment.TEST)
        assertEquals(QrPayloadV2Codec.Environment.TEST, QrPayloadV2Codec.decode(QrPayloadV2Codec.encode(test))!!.environment)
    }

    @Test
    fun `non-default port, colon-separated fingerprint, empty alias and a long alias all round-trip`() {
        val odd = phoneReceive.copy(
            port = 47821,
            fp = fp.chunked(2).joinToString(":").uppercase(),
            alias = "",
        )
        val decoded = QrPayloadV2Codec.decode(QrPayloadV2Codec.encode(odd))!!
        assertEquals(47821, decoded.port)
        assertEquals(fp, decoded.fp)
        assertEquals("", decoded.alias)
        // 24-byte cap, cut between characters: 12 × "中" is 36 bytes; 8 fit.
        val long = QrPayloadV2Codec.decode(QrPayloadV2Codec.encode(phoneReceive.copy(alias = "中".repeat(12))))!!
        assertEquals("中".repeat(8), long.alias)
    }

    @Test
    fun `payloads this build must never produce are refused at encode time`() {
        assertEncodeFails(phoneReceive.copy(fp = "abcd"))
        assertEncodeFails(phoneReceive.copy(pin = "12345"))
        assertEncodeFails(phoneReceive.copy(pin = "12a456"))
        assertEncodeFails(phoneReceive.copy(ips = List(5) { "10.0.0.$it" }))
        assertEncodeFails(phoneReceive.copy(ip = "300.1.1.1", ips = emptyList()))
        assertEncodeFails(phoneReceive.copy(transferKey = "c2hvcnQ="))
    }

    @Test
    fun `every agreed reject decodes to null without throwing`() {
        val a = hex(QrPayloadV2Vectors.A_HEX)
        fun mutated(index: Int, value: Int) = a.copyOf().also { it[index] = value.toByte() }
        assertNull("magic", QrPayloadV2Codec.decode(mutated(0, 0x01)))
        assertNull("reserved flag", QrPayloadV2Codec.decode(mutated(1, 0x16)))
        assertNull("nAddr=0", QrPayloadV2Codec.decode(mutated(2, 0)))
        assertNull("nAddr=5", QrPayloadV2Codec.decode(mutated(2, 5)))
        assertNull("pin nibble", QrPayloadV2Codec.decode(mutated(3 + 8 + 32 + 1, 0x3a)))
        assertNull("env=2", QrPayloadV2Codec.decode(mutated(3 + 8 + 32 + 3 + 32 + 12, 2)))
        assertNull("trailing byte", QrPayloadV2Codec.decode(a + byteArrayOf(0)))
        assertNull("empty", QrPayloadV2Codec.decode(ByteArray(0)))
        for (cut in 1 until a.size) assertNull("truncated at $cut", QrPayloadV2Codec.decode(a.copyOf(cut)))
        // alias length claims more than remains
        assertNull("alias overrun", QrPayloadV2Codec.decode(mutated(3 + 8 + 32 + 3 + 32, 200)))
        // hasPort with port 0
        val withPort = QrPayloadV2Codec.encode(phoneReceive.copy(port = 1))
        assertNull("port=0", QrPayloadV2Codec.decode(withPort.copyOf().also { it[3 + 8] = 0; it[3 + 8 + 1] = 0 }))
        // alias bytes that are not UTF-8
        val badAlias = a.copyOf().also { it[3 + 8 + 32 + 3 + 32 + 1] = 0xFF.toByte() }
        assertNull("alias utf8", QrPayloadV2Codec.decode(badAlias))
    }

    @Test
    fun `the Base45 rejects from the vector table`() {
        val text = QrPayloadV2Vectors.A_BASE45
        assertNotNull(Base45.decode(text))
        assertNull(Base45.decode(text.dropLast(2)))
        assertNull(Base45.decode(text.dropLast(3) + ":::"))
        assertNull(Base45.decode(text + "::"))
    }

    private fun assertEncodeFails(p: QrPayload) {
        val result = runCatching { QrPayloadV2Codec.encode(p) }
        assertEquals("expected encode to fail for $p", true, result.isFailure)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
