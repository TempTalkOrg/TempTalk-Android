package com.difft.android.base.storage.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.base.utils.globalServices
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Robolectric unit tests for [SecureUserSpMigration] — the legacy standalone-key
 * fallback fix for "1.1.8-era" users (auth-migration-fallback).
 *
 * Production opens the legacy blob via [androidx.security.crypto.EncryptedSharedPreferences],
 * whose `create()` needs the AndroidKeyStore provider that Robolectric does not supply
 * (`NoSuchAlgorithmException`). Tests therefore inject a plain in-memory `SharedPreferences`
 * through the `legacyPrefsProvider` seam — the migration logic (blob projection,
 * fill-empty-only fallback, marker stamping) is identical regardless of the prefs backend.
 *
 * The migration reads [globalServices].gson for the blob; we stub the top-level property
 * via MockK so the test doesn't depend on Hilt wiring.
 *
 * Cases:
 *  A. standalone-only legacy → fields populated from standalone keys, marker stamped.
 *  B. normal blob (non-empty baseAuth, empty standalone) → unchanged.
 *  D. no-clobber (blob baseAuth non-empty + standalone different) → blob value preserved.
 *  E. fresh install (no secure_prefs) → no crash, marker stamped, all empty.
 *  F. idempotency (migrationV1Completed=true) → shouldMigrate false.
 *  G. unrecoverable (blob baseAuth empty + standalone absent) → baseAuth stays empty, re-login.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SecureUserSpMigrationTest {

    private lateinit var context: Context
    private lateinit var legacyPrefs: SharedPreferences

    private companion object {
        const val KEY_USERDATA =
            "com.difft.chative.base.user.SimpleUserManager\$Companion.SHARED_PREFERENCES_KEY_USERDATA"
        const val KEY_BASIC_AUTH = "basic_auth"
        const val KEY_MICRO_TOKEN = "micro_token"
        const val KEY_SIGNALING_KEY = "signaling_key"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Plain SharedPreferences stand-in for the production EncryptedSharedPreferences.
        legacyPrefs = context.getSharedPreferences("test_secure_prefs", Context.MODE_PRIVATE)
        legacyPrefs.edit().clear().commit()

        // Stub the top-level `globalServices` property (com.difft.android.base.utils.ExtensionsKt)
        // — the migration reads `globalServices.gson` to parse the legacy blob.
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val entryPoint = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { entryPoint.gson } returns Gson()
        every { globalServices } returns entryPoint
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        legacyPrefs.edit().clear().commit()
    }

    /** Migration wired to the injected in-memory prefs (legacy file "present"). */
    private fun migrationWithPrefs() = SecureUserSpMigration(context) { legacyPrefs }

    /** Migration with no legacy prefs (fresh install). */
    private fun migrationFreshInstall() = SecureUserSpMigration(context) { null }

    private fun seedStandalone(
        basicAuth: String? = null,
        microToken: String? = null,
        signalingKey: String? = null,
    ) {
        legacyPrefs.edit().apply {
            basicAuth?.let { putString(KEY_BASIC_AUTH, it) }
            microToken?.let { putString(KEY_MICRO_TOKEN, it) }
            signalingKey?.let { putString(KEY_SIGNALING_KEY, it) }
        }.commit()
    }

    private fun seedBlob(userData: UserData) {
        legacyPrefs.edit().putString(KEY_USERDATA, Gson().toJson(userData)).commit()
    }

    // A. Standalone-only legacy: empty blob baseAuth, standalone keys set.
    @Test
    fun `case A standalone-only legacy populates auth from standalone keys`() = runBlocking {
        // Blob has account but empty auth (old verifyLocalToken required account).
        seedBlob(UserData(account = "+12312345678", baseAuth = null, microToken = null))
        seedStandalone(basicAuth = "basic-XYZ", microToken = "micro-XYZ")

        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        assertEquals("+12312345678", result.account)
        assertEquals("basic-XYZ", result.baseAuth)
        assertEquals("micro-XYZ", result.microToken)
        assertEquals("", result.signalingKey) // not seeded
        assertTrue(result.migrationV1Completed)
    }

    // A'. Standalone-only with NO blob at all (key absent) — fallback still runs.
    // Defensive scenario: a genuinely logged-in user never hits this (they always have a blob
    // containing account). It only guards against an unexpected blob-less standalone state.
    @Test
    fun `case A standalone keys read even when blob key absent`() = runBlocking {
        seedStandalone(basicAuth = "basic-NOBLOB", signalingKey = "sig-NOBLOB")

        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        assertEquals("basic-NOBLOB", result.baseAuth)
        assertEquals("sig-NOBLOB", result.signalingKey)
        assertEquals("", result.microToken) // not seeded → empty
        assertEquals("", result.account)
        assertTrue(result.migrationV1Completed)
    }

    // B. Normal blob: non-empty baseAuth, empty standalone → unchanged.
    @Test
    fun `case B normal blob not clobbered by empty standalone keys`() = runBlocking {
        seedBlob(
            UserData(
                account = "+12312345678",
                baseAuth = "blob-auth",
                microToken = "blob-micro",
                signalingKey = "blob-sig",
            ),
        )
        // No standalone keys seeded.

        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        assertEquals("blob-auth", result.baseAuth)
        assertEquals("blob-micro", result.microToken)
        assertEquals("blob-sig", result.signalingKey)
        assertTrue(result.migrationV1Completed)
    }

    // D. No-clobber (fill-empty-only): blob baseAuth non-empty + standalone DIFFERENT value
    // → blob value preserved, standalone ignored.
    @Test
    fun `case D no clobber preserves blob value over standalone`() = runBlocking {
        seedBlob(
            UserData(
                account = "+12312345678",
                baseAuth = "FRESH-blob",
                microToken = "FRESH-micro",
            ),
        )
        seedStandalone(basicAuth = "STALE-standalone", microToken = "STALE-micro")

        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        // Non-empty blob values win; standalone ignored.
        assertEquals("FRESH-blob", result.baseAuth)
        assertEquals("FRESH-micro", result.microToken)
        assertTrue(result.migrationV1Completed)
    }

    // E. Fresh install: no secure_prefs → no crash, marker stamped, all empty.
    @Test
    fun `case E fresh install stamps marker and stays empty`() = runBlocking {
        val result = migrationFreshInstall().migrate(UserAuthData.EMPTY)

        assertEquals("", result.baseAuth)
        assertEquals("", result.microToken)
        assertEquals("", result.signalingKey)
        assertEquals("", result.account)
        assertTrue(result.migrationV1Completed)
    }

    // G. Unrecoverable: blob has account but empty baseAuth, and standalone basic_auth is also
    // absent → nothing to recover. baseAuth stays "", account comes from the blob, marker is
    // stamped, and the fill-empty fallback does not fire (no standalone value to fill from).
    // This is the correct expected behavior — the user has no recoverable credentials and must
    // re-login on the next 401; this fix neither can nor should fabricate them.
    @Test
    fun `case G unrecoverable when blob baseAuth empty and standalone also empty`() = runBlocking {
        // Blob has account but no auth; no standalone keys seeded at all.
        seedBlob(UserData(account = "+12312345678", baseAuth = null, microToken = null))

        val result = migrationWithPrefs().migrate(UserAuthData.EMPTY)

        assertEquals("", result.baseAuth) // no blob value, no standalone value → stays empty
        assertEquals("+12312345678", result.account) // account still recovered from blob
        assertTrue(result.migrationV1Completed)
    }

    // F. Idempotency: migrationV1Completed=true → shouldMigrate false.
    @Test
    fun `case F shouldMigrate false when v1 already completed`() = runBlocking {
        val done = UserAuthData(migrationV1Completed = true)
        assertFalse(migrationWithPrefs().shouldMigrate(done))
    }

    // shouldMigrate true on a fresh (pre-v1) payload.
    @Test
    fun `shouldMigrate true when v1 not completed`() = runBlocking {
        assertTrue(migrationWithPrefs().shouldMigrate(UserAuthData.EMPTY))
    }
}
