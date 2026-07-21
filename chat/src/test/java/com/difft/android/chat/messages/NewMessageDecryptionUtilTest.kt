package com.difft.android.chat.messages

import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.google.gson.Gson
import com.google.protobuf.ByteString
import io.mockk.MockKAnnotations
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope

/**
 * Security regression test for the E2E decryption whitelist.
 *
 * `Envelope.type` is a server-controlled field. Before the fix, any type other
 * than ENCRYPTEDTEXT(8) fell through an `else` branch that parsed the raw,
 * unauthenticated `content` as a genuine [org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content]
 * message — letting a malicious/compromised server forge messages from any
 * sender by simply setting type=PLAINTEXT(7) (or any legacy type).
 *
 * The contract this test pins:
 *  - Only ENCRYPTEDTEXT(8) may reach the decrypt path (verified indirectly:
 *    everything else is dropped without touching the identity keys).
 *  - NOTIFY(6) stays a first-class plaintext control-signal path (server-driven
 *    group/contact updates), returned as a notify message, never as a chat Content.
 *  - Every other type (UNKNOWN/CIPHERTEXT/KEY_EXCHANGE/PREKEY_BUNDLE/RECEIPT/
 *    PLAINTEXT) is dropped (`decrypt` returns null) with no decryption attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NewMessageDecryptionUtilTest {

    private lateinit var encryptionDataManager: EncryptionDataManager
    private lateinit var util: NewMessageDecryptionUtil

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        encryptionDataManager = mockk(relaxed = true)
        util = NewMessageDecryptionUtil(encryptionDataManager, Gson())
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    private fun envelope(type: Int, content: ByteArray): Envelope =
        Envelope.newBuilder()
            .setType(Envelope.Type.forNumber(type))
            .setTimestamp(1_700_000_000_000L)
            .setContent(ByteString.copyFrom(content))
            .build()

    @Test
    fun `non-whitelisted server-controlled types are dropped without decryption`() {
        // Types a malicious server could set to bypass E2E. PLAINTEXT(7) is the
        // headline case; the legacy types share the same (removed) else branch.
        val forgeableTypes = listOf(
            Envelope.Type.UNKNOWN_VALUE,       // 0
            Envelope.Type.CIPHERTEXT_VALUE,    // 1
            Envelope.Type.KEY_EXCHANGE_VALUE,  // 2
            Envelope.Type.PREKEY_BUNDLE_VALUE, // 3
            Envelope.Type.RECEIPT_VALUE,       // 5
            Envelope.Type.PLAINTEXT_VALUE,     // 7
        )

        forgeableTypes.forEach { type ->
            // A well-formed Content that, pre-fix, would have been happily rendered.
            val forgedContent = org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
                .newBuilder()
                .setDataMessage(
                    org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage
                        .newBuilder()
                        .setBody("forged by server")
                )
                .build()

            val result = util.decrypt(envelope(type, forgedContent.toByteArray()))

            assertNull("type=$type must be dropped, not rendered", result)
        }

        // No identity-key access at all → no decryption was even attempted for
        // any dropped type. This is what guarantees the server can't smuggle
        // content through a non-encrypted envelope type.
        verify(exactly = 0) { encryptionDataManager.getAciIdentityKey() }
        verify(exactly = 0) { encryptionDataManager.getAciIdentityOldKey() }
        confirmVerified(encryptionDataManager)
    }

    @Test
    fun `NOTIFY stays a plaintext control-signal path`() {
        val notifyJson = """{"notifyType":1}"""
        val result = util.decrypt(envelope(Envelope.Type.NOTIFY_VALUE, notifyJson.toByteArray()))

        assertNotNull("NOTIFY must still be delivered", result)
        assertNotNull("NOTIFY must produce a notify message", result!!.signalCustomNotifyMessage)
        assertNull("NOTIFY must not be parsed as a chat Content", result.signalServiceContent)
        assertEquals(1, result.signalCustomNotifyMessage!!.notifyType)
    }
}
