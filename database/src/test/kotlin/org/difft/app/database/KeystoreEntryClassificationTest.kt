package org.difft.app.database

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract tests for [WCDBKeyManager.getKeystoreEntry]. The old return type `SecretKey?` fused
 * three distinct states into `null`; [KeystoreEntryResult] disambiguates them: alias not present →
 * [KeystoreEntryResult.Absent]; the Keystore op threw → [KeystoreEntryResult.Failed] carrying the
 * real cause (previously swallowed to `null`); a usable entry → [KeystoreEntryResult.Found].
 *
 * The AndroidKeyStore provider isn't emulated by Robolectric, so it's faked via
 * `mockkStatic(KeyStore::class)`. The real end-to-end throw-site mapping needs a real Keystore and
 * is documented as an `@Ignore`-d instrumentation contract below.
 */
class KeystoreEntryClassificationTest {

    private lateinit var ks: KeyStore

    @Before
    fun setUp() {
        mockkStatic(KeyStore::class)
        ks = mockk(relaxed = true)
        every { KeyStore.getInstance("AndroidKeyStore") } returns ks
        every { ks.load(null) } just Runs
    }

    @After
    fun tearDown() {
        unmockkStatic(KeyStore::class)
    }

    @Test
    fun `L2-CLASSIFY a absent alias maps to Absent`() {
        every { ks.containsAlias(any()) } returns false

        val result = WCDBKeyManager.getKeystoreEntry("WCDBKey")

        assertEquals(KeystoreEntryResult.Absent, result)
    }

    @Test
    fun `L2-CLASSIFY b thrown keystore op maps to Failed carrying the cause`() {
        val boom = ProviderException("keystore backend down")
        every { ks.containsAlias(any()) } returns true
        every { ks.getEntry(any(), any()) } throws boom

        val result = WCDBKeyManager.getKeystoreEntry("WCDBKey")

        assertTrue(result is KeystoreEntryResult.Failed, "a thrown op must map to Failed")
        assertSame(boom, result.cause, "cause must be preserved, not swallowed")
    }

    @Test
    fun `L2-CLASSIFY c usable entry maps to Found`() {
        val secret = mockk<SecretKey>()
        val entry = mockk<KeyStore.SecretKeyEntry>()
        every { entry.secretKey } returns secret
        every { ks.containsAlias(any()) } returns true
        every { ks.getEntry(any(), any()) } returns entry

        val result = WCDBKeyManager.getKeystoreEntry("WCDBKey")

        assertTrue(result is KeystoreEntryResult.Found, "a usable entry must map to Found")
        assertSame(secret, result.key)
    }

    /**
     * End-to-end throw-site mapping in `getOrCreateKey`: clean-absent (alias deleted, key file
     * kept) → `WCDBKeyUnavailableException` with `cause == null`; forced read throw → `cause`
     * preserved. Requires a real AndroidKeyStore; runs via instrumentation.
     */
    @Test
    @Ignore("Requires real AndroidKeyStore — not emulated by Robolectric; run via instrumentation")
    fun `L2-THROWSITE clean-absent has null cause, threw preserves cause`() {
        // Documented contract only — see integration tier.
    }
}
