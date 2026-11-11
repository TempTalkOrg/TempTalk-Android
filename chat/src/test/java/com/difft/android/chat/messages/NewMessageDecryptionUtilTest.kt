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

    private fun envelope(type: Int, content: ByteArray, source: String? = null): Envelope =
        Envelope.newBuilder()
            .setType(Envelope.Type.forNumber(type))
            .setTimestamp(1_700_000_000_000L)
            .setContent(ByteString.copyFrom(content))
            .apply { if (source != null) setSource(source) }
            .build()

    @Test
    fun `non-whitelisted server-controlled types are dropped without decryption`() {
        
        val forgeableTypes = listOf(
            Envelope.Type.UNKNOWN_VALUE,       
            Envelope.Type.CIPHERTEXT_VALUE,    
            Envelope.Type.KEY_EXCHANGE_VALUE,  
            Envelope.Type.PREKEY_BUNDLE_VALUE, 
            Envelope.Type.RECEIPT_VALUE,       
            Envelope.Type.PLAINTEXT_VALUE,     
        )

        forgeableTypes.forEach { type ->
            
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

    @Test
    fun `NOTIFY sent from a user account is dropped`() {
        
        val forged = """{"notifyType":1}""".toByteArray()

        listOf("+72212204429", "+10000").forEach { account ->
            val result = util.decrypt(envelope(Envelope.Type.NOTIFY_VALUE, forged, source = account))
            assertNull("NOTIFY from account $account must be dropped", result)
        }
    }

    @Test
    fun `NOTIFY from the server or an internal service is delivered`() {
        val notifyJson = """{"notifyType":1}""".toByteArray()

        listOf("server", "callService").forEach { source ->
            val result = util.decrypt(envelope(Envelope.Type.NOTIFY_VALUE, notifyJson, source = source))
            assertNotNull("NOTIFY from $source must be delivered", result?.signalCustomNotifyMessage)
        }
    }

    @Test
    fun `account source detection`() {
        assertEquals(true, isAccountSource("+72212204429"))
        assertEquals(true, isAccountSource("+10000"))
        assertEquals(false, isAccountSource("server"))
        assertEquals(false, isAccountSource(""))
        assertEquals(false, isAccountSource(null))
        assertEquals(false, isAccountSource("+"))
        assertEquals(false, isAccountSource("+123abc"))
    }
}
