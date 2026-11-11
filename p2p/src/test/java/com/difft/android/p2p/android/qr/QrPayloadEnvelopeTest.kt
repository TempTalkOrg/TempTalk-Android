package com.difft.android.p2p.android.qr

import com.difft.android.p2p.engine.qr.QrPayloadV2Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner







@RunWith(RobolectricTestRunner::class)
class QrPayloadEnvelopeTest {

    private fun envelope(payload: String = QrPayloadV2Vectors.A_BASE64URL, query: String = "migrationV2"): String =
        "chative://transfer?version=1&transferMode=primary&certificateHash=YWJj&peerId=ZGVm&$query=$payload"

    @Test
    fun `a payload wrapped in the legacy transfer link decodes to the same thing as the bare payload`() {
        val fromEnvelope = QrPayloadCodec.decode(envelope())
        val fromBare = QrPayloadCodec.decode(QrPayloadV2Vectors.A_BASE45)
        assertNotNull(fromBare)
        assertEquals(fromBare, fromEnvelope)
        assertEquals("192.168.0.106", fromEnvelope?.ip)
        assertEquals(listOf("192.168.0.106", "192.168.43.1"), fromEnvelope?.ips)
        assertEquals(QrPayloadV2Vectors.KEY_BASE64, fromEnvelope?.transferKey)
    }

    
    @Test
    fun `the temptalk scheme and an upper-case scheme are the same envelope`() {
        val bare = QrPayloadCodec.decode(QrPayloadV2Vectors.A_BASE45)
        assertEquals(bare, QrPayloadCodec.decode(envelope().replace("chative://", "temptalk://")))
        assertEquals(bare, QrPayloadCodec.decode(envelope().replace("chative://", "CHATIVE://")))
    }

    
    @Test
    fun `line breaks are stripped, spaces are not`() {
        assertEquals(QrPayloadCodec.decode(QrPayloadV2Vectors.A_BASE45), QrPayloadCodec.decode(QrPayloadV2Vectors.A_BASE45 + "\r\n"))
        assertNull(QrPayloadCodec.decode(QrPayloadV2Vectors.A_BASE45.trimStart(' ').drop(1)))
    }

    




    @Test
    fun `a link without the payload parameter, or under another host, is not a pairing code`() {
        assertNull(QrPayloadCodec.decode("chative://transfer?version=1&transferMode=primary&certificateHash=YWJj&peerId=ZGVm"))
        assertNull(QrPayloadCodec.decode("tsdevice://linkdevice?uuid=abc&pub_key=def"))
        assertNull(QrPayloadCodec.decode("chative://linkdevice?uuid=abc&pub_key=def"))
        assertNull(QrPayloadCodec.decode("https://example.com/transfer?migrationV2=" + QrPayloadV2Vectors.A_BASE64URL))
    }

    
    @Test
    fun `a malformed payload parameter is rejected`() {
        assertNull(QrPayloadCodec.decode(envelope(payload = "e30")))                 
        assertNull(QrPayloadCodec.decode("chative://transfer?version=1&migrationV2=не-base64url"))
        assertNull(QrPayloadCodec.decode("chative://transfer?version=1&migrationV2="))
        assertNull(QrPayloadCodec.decode(envelope(payload = "A".repeat(4 * 600))))  
    }

    
    @Test
    fun `bare junk is refused`() {
        assertNull(QrPayloadCodec.decode("""{"v":1,"ip":"1.2.3.4","transferKey":"a2V5"}"""))
        assertNull(QrPayloadCodec.decode("123456"))
        assertNull(QrPayloadCodec.decode("hello"))
        assertNull(QrPayloadCodec.decode(QrPayloadV2Vectors.A_BASE45.dropLast(2)))
    }

    @Test
    fun `the text this app renders decodes back to the payload it was built from`() {
        val payload = QrPayloadCodec.decode(QrPayloadV2Vectors.C_BASE45)!!
        assertEquals(QrPayloadV2Vectors.C_BASE45, QrPayloadCodec.encodeText(payload))
        assertNotNull(QrPayloadCodec.encode(payload, sizePx = 300))
    }
}
