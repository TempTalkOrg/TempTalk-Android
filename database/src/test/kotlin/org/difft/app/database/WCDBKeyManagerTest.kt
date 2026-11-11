package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.log.WCDBKeyUnavailableException
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
















@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WCDBKeyManagerTest {

    private lateinit var context: Context

    private val keyFile: File
        get() = File(context.filesDir, "wcdb_key.bin")

    private val tempFile: File
        get() = File(context.filesDir, "wcdb_key.bin.tmp")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        keyFile.delete()
        tempFile.delete()
    }

    @After
    fun teardown() {
        keyFile.delete()
        tempFile.delete()
    }

    





    @Test
    fun `truncated key file throws WCDBKeyUnavailableException without keystore call`() {
        
        keyFile.writeBytes(ByteArray(10))

        try {
            WCDBKeyManager.getOrCreateKey(context)
            fail("Expected WCDBKeyUnavailableException for truncated key file")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
            assertTrue(
                e.message!!.contains("Corrupt") || e.message!!.contains("expected"),
                "Error message must indicate the wire-format size mismatch; was: '${e.message}'"
            )
        }
    }

    



    @Test
    fun `zero-byte key file throws WCDBKeyUnavailableException`() {
        keyFile.createNewFile() 

        try {
            WCDBKeyManager.getOrCreateKey(context)
            fail("Expected WCDBKeyUnavailableException for zero-byte key file")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
        }
    }

    


    @Test
    fun `oversize key file throws WCDBKeyUnavailableException`() {
        keyFile.writeBytes(ByteArray(200))

        try {
            WCDBKeyManager.getOrCreateKey(context)
            fail("Expected WCDBKeyUnavailableException for oversize key file")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
        }
    }

    

    







    @Test
    fun `cipherKeyForCurrentBuild returns null in debug build (plaintext, no keystore call)`() {
        assertEquals(
            null,
            WCDBKeyManager.cipherKeyForCurrentBuild(context),
            "debug build must use NO cipher key (plaintext), mirroring WCDB.kt's !BuildConfig.DEBUG gate",
        )
        
        assertEquals(
            false, keyFile.exists(),
            "debug cipherKeyForCurrentBuild must not generate/persist a key",
        )
    }

    

    





    @Test
    fun `installKey rejects a key that is not 48 bytes`() {
        try {
            WCDBKeyManager.installKey(context, ByteArray(32))
            fail("Expected IllegalArgumentException for a 32-byte key")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
            assertTrue(
                e.message!!.contains("48"),
                "Error must state the required 48-byte length; was: '${e.message}'"
            )
        }
        
        assertEquals(false, keyFile.exists(), "installKey must not write wcdb_key.bin on length rejection")
    }

    


    @Test
    fun `protectKey rejects a key that is not 48 bytes`() {
        try {
            WCDBKeyManager.protectKey(ByteArray(10))
            fail("Expected IllegalArgumentException for a 10-byte key")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
            assertTrue(e.message!!.contains("48"), "Error must state the required 48-byte length")
        }
    }

    



    @Test
    fun `unprotectKey rejects a blob that is not the 76-byte wire size`() {
        try {
            WCDBKeyManager.unprotectKey(ByteArray(40))
            fail("Expected WCDBKeyUnavailableException for a 40-byte blob")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
            assertTrue(
                e.message!!.contains("76") || e.message!!.contains("expected"),
                "Error must indicate the wire-format size mismatch; was: '${e.message}'"
            )
        }
    }

    
    
    
    

    




    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `installKey then getOrCreateKey returns the installed bytes`() {
        val key = ByteArray(48) { (it * 7 + 1).toByte() }
        WCDBKeyManager.installKey(context, key)
        val readBack = WCDBKeyManager.getOrCreateKey(context)
        assertTrue(key.contentEquals(readBack), "getOrCreateKey must return the bytes installKey wrote")
        assertEquals(76, keyFile.length(), "installKey must produce the 76-byte wire format")
    }

    


    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `protectKey then unprotectKey returns the original key`() {
        val key = ByteArray(48) { (it xor 0x5A).toByte() }
        val blob = WCDBKeyManager.protectKey(key)
        assertEquals(76, blob.size, "protected blob must be the 76-byte wire format")
        val recovered = WCDBKeyManager.unprotectKey(blob)
        assertTrue(key.contentEquals(recovered), "unprotectKey must return the protected bytes")
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getOrCreateKey returns 48-byte plaintext on first call`() {
        val key = WCDBKeyManager.getOrCreateKey(context)
        assertEquals(48, key.size, "WCDB cipher plaintext must be exactly 48 bytes")
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getOrCreateKey returns the same key on subsequent calls`() {
        val first = WCDBKeyManager.getOrCreateKey(context)
        val second = WCDBKeyManager.getOrCreateKey(context)
        assertTrue(first.contentEquals(second), "WCDB cipher key must be stable across calls")
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getOrCreateKey produces a 76-byte wire-format file`() {
        WCDBKeyManager.getOrCreateKey(context)
        assertTrue(keyFile.exists(), "wcdb_key.bin must exist after getOrCreateKey")
        assertEquals(
            76, keyFile.length(),
            "wcdb_key.bin must be exactly 12 IV + 48 ciphertext + 16 tag = 76 bytes"
        )
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getOrCreateKey leaves no temp file after success`() {
        WCDBKeyManager.getOrCreateKey(context)
        assertEquals(false, tempFile.exists(), "temp file must not survive a successful atomic write")
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `tampered GCM tag throws WCDBKeyUnavailableException`() {
        WCDBKeyManager.getOrCreateKey(context)
        val bytes = keyFile.readBytes()
        for (i in bytes.size - 16 until bytes.size) {
            bytes[i] = (bytes[i].toInt() xor 0xFF).toByte()
        }
        keyFile.writeBytes(bytes)
        try {
            WCDBKeyManager.getOrCreateKey(context)
            fail("Expected WCDBKeyUnavailableException for tampered key file")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
        }
    }
}
