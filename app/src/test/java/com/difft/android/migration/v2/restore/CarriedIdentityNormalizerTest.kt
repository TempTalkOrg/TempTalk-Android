package com.difft.android.migration.v2.restore

import com.difft.android.base.utils.Base64
import com.difft.android.migration.v2.model.NeutralIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The identity a container carries is checked before it is installed, because a key that cannot decode
 * only fails at sign-in's second request — after the first has already revoked the old device. The
 * one peer-side slip we normalize is the bare 32-byte public key (what iOS shipped on 2026-09-09);
 * everything else must be rejected before promote.
 */
class CarriedIdentityNormalizerTest {

    private val raw32 = ByteArray(32) { (it + 0xA0).toByte() }        // first byte 0xA0 — looks like a bad type marker
    private val serialized33 = byteArrayOf(0x05) + raw32
    private val priv32 = ByteArray(32) { it.toByte() }

    @Test
    fun `a serialized 33-byte public key passes through unchanged`() {
        val out = CarriedIdentityNormalizer.normalize(identity(pub = serialized33, priv = priv32))

        assertArrayEquals(serialized33, Base64.decode(out.aciPublicKey))
        assertArrayEquals(priv32, Base64.decode(out.aciPrivateKey))
    }

    @Test
    fun `a bare 32-byte public key gets the 0x05 type byte prepended`() {
        // Exactly what libsignal would otherwise choke on: its first byte read as the type marker.
        val out = CarriedIdentityNormalizer.normalize(identity(pub = raw32, priv = priv32))

        assertArrayEquals(serialized33, Base64.decode(out.aciPublicKey))
    }

    @Test
    fun `unpadded base64 is accepted`() {
        val unpadded = Base64.encodeBytesWithoutPadding(serialized33)

        val out = CarriedIdentityNormalizer.normalize(identity(pubB64 = unpadded, priv = priv32))

        assertArrayEquals(serialized33, Base64.decode(out.aciPublicKey))
    }

    @Test
    fun `a public key of any other length is rejected`() {
        assertThrows(CarriedIdentityNormalizer.InvalidIdentity::class.java) {
            CarriedIdentityNormalizer.normalize(identity(pub = ByteArray(31), priv = priv32))
        }
        assertThrows(CarriedIdentityNormalizer.InvalidIdentity::class.java) {
            CarriedIdentityNormalizer.normalize(identity(pub = ByteArray(34), priv = priv32))
        }
    }

    @Test
    fun `a 33-byte public key with the wrong type byte is rejected`() {
        val wrongType = byteArrayOf(0x04) + raw32

        assertThrows(CarriedIdentityNormalizer.InvalidIdentity::class.java) {
            CarriedIdentityNormalizer.normalize(identity(pub = wrongType, priv = priv32))
        }
    }

    @Test
    fun `a private key that is not 32 bytes is rejected`() {
        assertThrows(CarriedIdentityNormalizer.InvalidIdentity::class.java) {
            CarriedIdentityNormalizer.normalize(identity(pub = serialized33, priv = ByteArray(33)))
        }
    }

    @Test
    fun `garbage that is not base64 is rejected rather than crashing later`() {
        assertThrows(CarriedIdentityNormalizer.InvalidIdentity::class.java) {
            CarriedIdentityNormalizer.normalize(identity(pubB64 = "not base64!!", priv = priv32))
        }
    }

    @Test
    fun `the old pair follows the same rules and a half pair is dropped`() {
        val withOld = identity(pub = serialized33, priv = priv32).copy(
            oldAciPublicKey = Base64.encodeBytes(raw32),          // bare → normalized
            oldAciPrivateKey = Base64.encodeBytes(priv32),
        )
        val out = CarriedIdentityNormalizer.normalize(withOld)
        assertArrayEquals(serialized33, Base64.decode(out.oldAciPublicKey!!))

        val half = identity(pub = serialized33, priv = priv32).copy(oldAciPublicKey = Base64.encodeBytes(raw32))
        val dropped = CarriedIdentityNormalizer.normalize(half)
        assertNull(dropped.oldAciPublicKey)
        assertNull(dropped.oldAciPrivateKey)
    }

    @Test
    fun `everything else on the record is left alone`() {
        val out = CarriedIdentityNormalizer.normalize(
            identity(pub = serialized33, priv = priv32).copy(identityKeyGenTime = 1_779_676_020_768, favKey = "fk"),
        )

        assertEquals(1_779_676_020_768, out.identityKeyGenTime)
        assertEquals("fk", out.favKey)
    }

    private fun identity(pub: ByteArray = serialized33, priv: ByteArray = priv32, pubB64: String? = null) = NeutralIdentity(
        aciPublicKey = pubB64 ?: Base64.encodeBytes(pub),
        aciPrivateKey = Base64.encodeBytes(priv),
    )
}
