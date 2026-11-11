package com.difft.android.base.glide

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File



















@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlideCacheKeyManagerTest {

    private lateinit var context: Context

    private val keyFile: File
        get() = File(context.filesDir, "glide_cache_key.bin")

    private val tempFile: File
        get() = File(context.filesDir, "glide_cache_key.bin.tmp")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        resetCachedKey()
        
        keyFile.delete()
        tempFile.delete()
    }

    @After
    fun teardown() {
        resetCachedKey()
        keyFile.delete()
        tempFile.delete()
    }

    
    private fun resetCachedKey() {
        GlideCacheKeyManager::class.java.getDeclaredField("cached").apply {
            isAccessible = true
            set(GlideCacheKeyManager, null)
        }
        GlideCacheKeyManager::class.java.getDeclaredField("warmState").apply {
            isAccessible = true
            setInt(GlideCacheKeyManager, 0) 
        }
        
        
        GlideCacheKeyManager::class.java.getDeclaredField("warming").apply {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (get(GlideCacheKeyManager) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        }
    }

    private fun assertNullOr32Bytes(key: ByteArray?) {
        assertTrue("key must be null or exactly 32 bytes, was ${key?.size}", key == null || key.size == 32)
    }

    @Test
    fun `getKeyOrNull degrades gracefully without throwing on a clean slate`() {
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
        assertEquals(key != null, GlideCacheKeyManager.isAvailable(context))
    }

    @Test
    fun `getKeyOrNull does not crash on a too-small key file`() {
        keyFile.writeBytes(ByteArray(10))
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
    }

    @Test
    fun `getKeyOrNull does not crash on a zero-byte key file`() {
        keyFile.createNewFile()
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
    }

    @Test
    fun `getKeyOrNull does not crash on an oversize key file`() {
        keyFile.writeBytes(ByteArray(200))
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
    }

    @Test
    fun `getKeyOrNull does not crash on a correct-size but undecryptable key file`() {
        
        
        keyFile.writeBytes(ByteArray(60) { it.toByte() })
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
    }

    @Test
    fun `repeated calls return a stable result`() {
        val first = GlideCacheKeyManager.getKeyOrNull(context)
        val second = GlideCacheKeyManager.getKeyOrNull(context)
        if (first == null) {
            assertNull("result must stay null while the environment is unchanged", second)
        } else {
            assertTrue("cached key must be stable across calls", first.contentEquals(second!!))
        }
    }

    @Test
    fun `isAvailable never throws regardless of key file state`() {
        keyFile.writeBytes(ByteArray(3))
        
        GlideCacheKeyManager.isAvailable(context)
    }

    @Test
    fun `isCacheKeyReady returns false on a clean slate without blocking`() {
        
        
        assertFalse(GlideCacheKeyManager.isCacheKeyReady(context))
    }

    @Test
    fun `isCacheKeyReady agrees with getKeyOrNull once resolved`() {
        
        
        
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertEquals(key != null, GlideCacheKeyManager.isCacheKeyReady(context))
    }

    @Test
    fun `isCacheKeyReady never throws regardless of key file state`() {
        keyFile.writeBytes(ByteArray(3))
        
        GlideCacheKeyManager.isCacheKeyReady(context)
    }

    
    
    

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getKeyOrNull returns a stable 32-byte key and persists a 60-byte file`() {
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertEquals(32, key!!.size)
        assertTrue(keyFile.exists())
        assertEquals(60L, keyFile.length()) 

        resetCachedKey()
        val reloaded = GlideCacheKeyManager.getKeyOrNull(context)
        assertTrue("key must round-trip from disk", key.contentEquals(reloaded!!))
        assertTrue("no temp file should survive", !tempFile.exists())
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `isCacheKeyReady becomes true after the key resolves`() {
        
        
        assertEquals(32, GlideCacheKeyManager.getKeyOrNull(context)!!.size)
        assertTrue("isCacheKeyReady must be true once the key resolves", GlideCacheKeyManager.isCacheKeyReady(context))
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `self-heal regenerates a usable key when the key file is unreadable`() {
        
        keyFile.writeBytes(ByteArray(60) { it.toByte() })

        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertEquals(32, key!!.size)
        assertEquals(60L, keyFile.length()) 

        resetCachedKey()
        val reloaded = GlideCacheKeyManager.getKeyOrNull(context)
        assertTrue("regenerated key must round-trip", key.contentEquals(reloaded!!))
    }
}
