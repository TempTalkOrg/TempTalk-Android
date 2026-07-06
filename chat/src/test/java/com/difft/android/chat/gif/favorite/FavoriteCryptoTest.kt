package com.difft.android.chat.gif.favorite

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip + tamper tests for [FavoriteCrypto] (AES-256-GCM blob, byte-exact JSON).
 * Robolectric provides real android.util.Base64. Covers the FavoriteCrypto row of the
 * Test Inventory (encrypt -> decrypt fidelity, wrong key fails, tampered blob fails).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FavoriteCryptoTest {

    private fun record(hash: String, version: Long) = FavoriteRecord(
        attachment = FavoriteAttachmentPointer(
            id = "att-$hash",
            authorizeId = 42L,
            key = byteArrayOf(1, 2, 3, 4, 5),
            digest = byteArrayOf(9, 8, 7),
            fileHash = hash,
            contentType = "image/gif",
            width = 200,
            height = 150
        ),
        addedListVersion = version
    )

    @Test
    fun `encrypt then decrypt round-trips records`() {
        val key = FavoriteCrypto.generateFavKey()
        val list = FavoriteListPlain(listOf(record("h1", 10L), record("h2", 11L)))

        val blob = FavoriteCrypto.encrypt(key, list)
        val decrypted = assertNotNull(FavoriteCrypto.decrypt(key, blob))

        assertEquals(2, decrypted.records.size)
        val r1 = decrypted.records.first { it.attachment.fileHash == "h1" }
        assertEquals(10L, r1.addedListVersion)
        assertEquals(42L, r1.attachment.authorizeId)
        assertEquals(200, r1.attachment.width)
        assertEquals("image/gif", r1.attachment.contentType)
        assertTrue(r1.attachment.key.contentEquals(byteArrayOf(1, 2, 3, 4, 5)))
        assertTrue(r1.attachment.digest.contentEquals(byteArrayOf(9, 8, 7)))
    }

    @Test
    fun `empty list round-trips`() {
        val key = FavoriteCrypto.generateFavKey()
        val blob = FavoriteCrypto.encrypt(key, FavoriteListPlain(emptyList()))
        val decrypted = assertNotNull(FavoriteCrypto.decrypt(key, blob))
        assertEquals(0, decrypted.records.size)
    }

    @Test
    fun `wrong key fails to decrypt`() {
        val key = FavoriteCrypto.generateFavKey()
        val wrongKey = FavoriteCrypto.generateFavKey()
        val blob = FavoriteCrypto.encrypt(key, FavoriteListPlain(listOf(record("h1", 1L))))
        assertNull(FavoriteCrypto.decrypt(wrongKey, blob))
    }

    @Test
    fun `tampered blob fails to decrypt`() {
        val key = FavoriteCrypto.generateFavKey()
        val blob = FavoriteCrypto.encrypt(key, FavoriteListPlain(listOf(record("h1", 1L))))
        // Flip a character near the end (ciphertext/tag region).
        val tampered = blob.dropLast(2) + if (blob.last() == 'A') "BB" else "AA"
        assertNull(FavoriteCrypto.decrypt(key, tampered))
    }

    @Test
    fun `decrypt of garbage returns null not throw`() {
        val key = FavoriteCrypto.generateFavKey()
        assertNull(FavoriteCrypto.decrypt(key, "not-a-valid-blob!!!"))
    }

    // ---- v2 key-wrapping (KEK = HKDF(aci identity private key)) ----

    @Test
    fun `wrap then unwrap round-trips favKey`() {
        val priv = ByteArray(32) { it.toByte() }
        val kek = FavoriteCrypto.deriveKek(priv)
        val favKey = FavoriteCrypto.generateFavKey()

        val wrapped = FavoriteCrypto.wrapFavKey(kek, favKey)
        val unwrapped = assertNotNull(FavoriteCrypto.unwrapFavKey(kek, wrapped))

        assertTrue(favKey.contentEquals(unwrapped))
    }

    @Test
    fun `unwrap with a KEK from a different identity fails`() {
        val kek = FavoriteCrypto.deriveKek(ByteArray(32) { 1 })
        val wrongKek = FavoriteCrypto.deriveKek(ByteArray(32) { 2 })
        val wrapped = FavoriteCrypto.wrapFavKey(kek, FavoriteCrypto.generateFavKey())
        assertNull(FavoriteCrypto.unwrapFavKey(wrongKek, wrapped))
    }

    @Test
    fun `unwrap of garbage envelope returns null not throw`() {
        val kek = FavoriteCrypto.deriveKek(ByteArray(32) { 3 })
        assertNull(FavoriteCrypto.unwrapFavKey(kek, "not-json"))
        assertNull(FavoriteCrypto.unwrapFavKey(kek, "{\"v\":1}"))
    }

    @Test
    fun `deriveKek is deterministic for the same identity key`() {
        val priv = ByteArray(32) { (it * 3).toByte() }
        val k1 = FavoriteCrypto.deriveKek(priv)
        val k2 = FavoriteCrypto.deriveKek(priv)
        assertEquals(FavoriteCrypto.FAV_KEY_SIZE, k1.size)
        assertTrue(k1.contentEquals(k2))
    }

    @Test
    fun `keyId is deterministic and differs across keys`() {
        val a = ByteArray(FavoriteCrypto.FAV_KEY_SIZE) { 7 }
        val b = ByteArray(FavoriteCrypto.FAV_KEY_SIZE) { 8 }
        assertEquals(FavoriteCrypto.keyId(a), FavoriteCrypto.keyId(a))
        assertNotEquals(FavoriteCrypto.keyId(a), FavoriteCrypto.keyId(b))
    }
}
