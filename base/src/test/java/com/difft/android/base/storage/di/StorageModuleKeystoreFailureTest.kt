package com.difft.android.base.storage.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.UnavailableDataStore
import com.difft.android.base.storage.schema.GlobalConfigData
import com.difft.android.base.storage.schema.UserAuthData
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.InvalidKeyException
import kotlin.test.assertFailsWith

/**
 * Regression + behaviour tests for the keystore-failure fallback in [StorageModule]
 * (crash 8d61a948). T6–T10 per design §"Test Case Inventory".
 *
 * Strategy (per design + the 8cd6fe30 "no tautological mockkStatic" lesson):
 *  - We let the **real** provider / `buildAeadOrNull` / `buildAead` chain run, and only
 *    make the low-level `AndroidKeysetManager.Builder.build()` throw — so the catch
 *    boundary and the stub-return are exercised through the genuine delegation path,
 *    not stubbed away.
 *  - `FirebaseCrashlytics.getInstance()` is not initialized under Robolectric, so it is
 *    statically mocked to a relaxed instance; T10 verifies the report calls on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StorageModuleKeystoreFailureTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var crashlytics: FirebaseCrashlytics

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Crashlytics is unavailable under Robolectric — stub getInstance() so the
        // report path does not itself throw, and so T10 can verify the calls.
        crashlytics = mockk(relaxed = true)
        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns crashlytics

        // Make the Tink keyset build fail as in the production crash.
        mockkConstructor(AndroidKeysetManager.Builder::class)
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    private fun makeKeysetBuildThrow(throwable: Throwable) {
        // Fluent setters return the (mocked) builder so the real chain runs; build() throws.
        // Explicit <Builder> type args: AndroidKeysetManager.Builder has two withKeyTemplate
        // overloads, so the lambda's return type must be pinned for overload resolution.
        every<AndroidKeysetManager.Builder> {
            anyConstructed<AndroidKeysetManager.Builder>().withSharedPref(any(), any(), any())
        } answers { self as AndroidKeysetManager.Builder }
        every<AndroidKeysetManager.Builder> {
            anyConstructed<AndroidKeysetManager.Builder>().withKeyTemplate(any<com.google.crypto.tink.KeyTemplate>())
        } answers { self as AndroidKeysetManager.Builder }
        every<AndroidKeysetManager.Builder> {
            anyConstructed<AndroidKeysetManager.Builder>().withMasterKeyUri(any())
        } answers { self as AndroidKeysetManager.Builder }
        every<AndroidKeysetManager> {
            anyConstructed<AndroidKeysetManager.Builder>().build()
        } throws throwable
    }

    // T6 — secure_user provider returns the stub (not a real encrypted DataStore) on keystore failure.
    @Test
    fun `T6 secure user provider returns UnavailableDataStore on InvalidKeyException`() = runTest {
        makeKeysetBuildThrow(InvalidKeyException("Keystore cannot load the key with ID: tt_storage_master_secure_user"))

        val store = StorageModule.provideSecureUserDataStore(context, scope)

        assertTrue("expected UnavailableDataStore", store is UnavailableDataStore)
        assertEquals(UserAuthData.EMPTY, store.data.first())
    }

    // T7 — secure_config provider returns the stub on keystore failure.
    @Test
    fun `T7 secure config provider returns UnavailableDataStore on InvalidKeyException`() = runTest {
        makeKeysetBuildThrow(InvalidKeyException("Keystore cannot load the key with ID: tt_storage_master_secure_config"))

        val store = StorageModule.provideSecureConfigDataStore(context, scope)

        assertTrue("expected UnavailableDataStore", store is UnavailableDataStore)
        assertEquals(GlobalConfigData.EMPTY, store.data.first())
    }

    // T8 — catch scope is narrow: a programming error (NPE) is NOT swallowed.
    @Test
    fun `T8 buildAeadOrNull rethrows non-keystore exceptions`() {
        makeKeysetBuildThrow(NullPointerException("programming bug — must not be swallowed"))

        assertFailsWith<NullPointerException> {
            StorageModule.buildAeadOrNull(
                context,
                "tt_storage_secure_user_keyset",
                "secure_user_keyset",
                "android-keystore://tt_storage_master_secure_user",
                namespace = "secure_user",
            )
        }
    }

    // T8b — buildAeadOrNull returns null (not throws) on a keystore GeneralSecurityException.
    @Test
    fun `T8b buildAeadOrNull returns null on keystore failure`() {
        makeKeysetBuildThrow(InvalidKeyException("Keystore cannot load the key"))

        val aead = StorageModule.buildAeadOrNull(
            context,
            "tt_storage_secure_user_keyset",
            "secure_user_keyset",
            "android-keystore://tt_storage_master_secure_user",
            namespace = "secure_user",
        )

        assertNull(aead)
        // Prove we hit the build() throw path, not an earlier AeadConfig.register() failure.
        verify { anyConstructed<AndroidKeysetManager.Builder>().build() }
    }

    // T10 — keystore failure is reported to Crashlytics with namespace + api custom keys.
    @Test
    fun `T10 keystore failure reports to Crashlytics with custom keys`() {
        clearMocks(crashlytics, answers = false)
        makeKeysetBuildThrow(InvalidKeyException("Keystore cannot load the key"))

        StorageModule.buildAeadOrNull(
            context,
            "tt_storage_secure_user_keyset",
            "secure_user_keyset",
            "android-keystore://tt_storage_master_secure_user",
            namespace = "secure_user",
        )

        verify { crashlytics.setCustomKey("keystore_fail_namespace", "secure_user") }
        verify { crashlytics.setCustomKey("keystore_fail_api", any<Int>()) }
        verify { crashlytics.recordException(any()) }
    }
}
