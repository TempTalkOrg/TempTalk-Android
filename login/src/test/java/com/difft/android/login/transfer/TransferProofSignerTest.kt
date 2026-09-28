package com.difft.android.login.transfer

import com.difft.android.base.utils.Base64
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey

/**
 * The proof's bytes are a cross-platform contract: every end must sign the same message and encode
 * the same way, or the server's verification fails for one of them. libsignal's types are mocked —
 * its native library is not on the JVM test classpath — so this pins the message and the encodings,
 * not the curve arithmetic.
 */
class TransferProofSignerTest {

    @Test
    fun `the signed message is the four tokens joined by a bar, as UTF-8`() {
        val bytes = TransferProofSigner.message("+12025550100", "AQID", "tok.en.jwt")

        assertEquals("tt-acct-transfer-v1|ik-possession|+12025550100|AQID|tok.en.jwt", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `build signs exactly that message with the identity's private key and encodes without padding`() {
        val publicBytes = ByteArray(33) { 5 }
        val signature = ByteArray(64) { 7 }
        val signed = slot<ByteArray>()
        val publicKey = mockk<IdentityKey>().also { every { it.serialize() } returns publicBytes }
        val privateKey = mockk<ECPrivateKey>().also { every { it.calculateSignature(capture(signed)) } returns signature }
        val pair = mockk<IdentityKeyPair>().also {
            every { it.publicKey } returns publicKey
            every { it.privateKey } returns privateKey
        }

        val proof = TransferProofSigner.build("+12025550100", pair, "tok.en.jwt")

        val identityKeyBase64 = Base64.encodeBytesWithoutPadding(publicBytes)
        assertEquals(identityKeyBase64, proof.identityKey)
        assertEquals(
            "tt-acct-transfer-v1|ik-possession|+12025550100|$identityKeyBase64|tok.en.jwt",
            signed.captured.toString(Charsets.UTF_8),
        )
        assertEquals(Base64.encodeBytesWithoutPadding(signature), proof.signature)
        assertFalse("standard alphabet, no padding", proof.signature.contains('=') || proof.signature.contains('-'))
    }
}
