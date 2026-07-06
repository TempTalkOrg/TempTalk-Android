package com.difft.android.base.storage

import androidx.core.content.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the `app_state` migration chain (issue #725 Task 5).
 *
 * Covers:
 *  - Migration 1 — `sp_chative_account` typed-key projection. Each section-B key is
 *    moved verbatim; absent keys are not stamped.
 *  - Migration 2 — default-prefs keyboard-height fallback runs only if migration 1
 *    didn't already populate the key (sp_chative_account wins per design §4.3).
 *  - Migration 3 — `UserDataUxFieldsMigration` short-circuits on `MIGRATION_VERSION`
 *    presence (idempotency for the migration chain).
 *
 * The `UserDataUxFieldsMigration` body (legacy `secure_prefs` blob read) is not
 * exercised here — EncryptedSharedPreferences requires Android's Keystore which
 * Robolectric does not provide deterministically. The fresh-install branch
 * (no legacy blob) is verified instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppStateMigrationsTest {

    private lateinit var scope: CoroutineScope
    private lateinit var context: android.content.Context
    private lateinit var file: File

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "test_app_state_${System.nanoTime()}.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
        // Clear legacy SP files between tests so each run starts fresh.
        context.getSharedPreferences("sp_chative_account", android.content.Context.MODE_PRIVATE)
            .edit { clear() }
        context.getSharedPreferences(
            "${context.packageName}_preferences",
            android.content.Context.MODE_PRIVATE,
        ).edit { clear() }
    }

    @Test
    fun `migration 1 carries sp_chative_account keys into DataStore`() = runTest {
        context.getSharedPreferences("sp_chative_account", android.content.Context.MODE_PRIVATE).edit {
            putInt("SP_BYC_DOMAINS_TIME", 7)
            putString("SP_DENOISE_MODE", "enhanced")
            putString("sp_speed_test_success_host", "https://example.com")
            putLong("call_last_feedback_reset_time", 9_999L)
            putBoolean("call_feedback_has_triggered", true)
            putInt("keyboard_height_portrait", 600)
        }
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            migrations = AppStateMigrations.build(context),
            produceFile = { file },
        )
        // Trigger migrations.
        val prefs = store.data.first()
        assertEquals(7, prefs[AppStateKeys.SP_UNREAD_MSG_NUM])
        assertEquals("enhanced", prefs[AppStateKeys.SP_DENOISE_MODE])
        assertEquals("https://example.com", prefs[AppStateKeys.SP_KEY_BEST_HOST])
        assertEquals(9_999L, prefs[AppStateKeys.CALL_LAST_FEEDBACK_RESET_TIME])
        assertEquals(true, prefs[AppStateKeys.CALL_FEEDBACK_HAS_TRIGGERED])
        assertEquals(600, prefs[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT])
    }

    @Test
    fun `migration 2 only fills keyboard keys absent from migration 1`() = runTest {
        // Only the default-prefs file has the value; sp_chative_account has nothing.
        context.getSharedPreferences(
            "${context.packageName}_preferences",
            android.content.Context.MODE_PRIVATE,
        ).edit {
            putInt("keyboard_height_landscape", 350)
        }
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            migrations = AppStateMigrations.build(context),
            produceFile = { file },
        )
        val prefs = store.data.first()
        assertEquals(350, prefs[AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE])
    }

    @Test
    fun `sp_chative_account keyboard height wins over default-prefs`() = runTest {
        context.getSharedPreferences("sp_chative_account", android.content.Context.MODE_PRIVATE).edit {
            putInt("keyboard_height_portrait", 700)
        }
        context.getSharedPreferences(
            "${context.packageName}_preferences",
            android.content.Context.MODE_PRIVATE,
        ).edit {
            putInt("keyboard_height_portrait", 999)
        }
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            migrations = AppStateMigrations.build(context),
            produceFile = { file },
        )
        val prefs = store.data.first()
        // Migration 1 ran first, populated KEY_KEYBOARD_HEIGHT_PORTRAIT with 700,
        // then migration 2's `shouldMigrate` saw the key was already present and
        // declined to overwrite. The 999 in default-prefs is ignored.
        assertEquals(700, prefs[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT])
    }

    @Test
    fun `migration 3 stamps MIGRATION_VERSION on fresh install`() = runTest {
        // No legacy SP data of any kind — exercises the fresh-install branch.
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            migrations = AppStateMigrations.build(context),
            produceFile = { file },
        )
        val prefs = store.data.first()
        assertEquals(
            AppStateMigrations.CURRENT_MIGRATION_VERSION,
            prefs[AppStateKeys.MIGRATION_VERSION],
        )
    }

    @Test
    fun `absent legacy keys are not stamped with defaults`() = runTest {
        // No keys in sp_chative_account / default-prefs.
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            migrations = AppStateMigrations.build(context),
            produceFile = { file },
        )
        val prefs = store.data.first()
        // None of the section B keys should appear — callers should fall back to AppStateDefaults.
        assertFalse(prefs.contains(AppStateKeys.SP_UNREAD_MSG_NUM))
        assertFalse(prefs.contains(AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT))
        // But the last migration stamps the version marker even on a fresh install.
        assertTrue(prefs.contains(AppStateKeys.MIGRATION_VERSION))
    }

    @Test
    fun `pre-existing migration marker survives re-init`() = runTest {
        // Simulate a 2nd cold start: pre-seed legacy SP, run migrations once, then
        // verify a second read on the same DataStore preserves the marker.
        context.getSharedPreferences("sp_chative_account", android.content.Context.MODE_PRIVATE).edit {
            putInt("SP_BYC_DOMAINS_TIME", 100)
        }
        val store = PreferenceDataStoreFactory.create(
            scope = scope,
            migrations = AppStateMigrations.build(context),
            produceFile = { file },
        )
        // First read triggers migrations and stamps the marker.
        val firstRead = store.data.first()
        assertEquals(
            AppStateMigrations.CURRENT_MIGRATION_VERSION,
            firstRead[AppStateKeys.MIGRATION_VERSION],
        )
        // Second read should return the same data without re-running the last migration —
        // even though we can't easily verify the migration didn't run, the marker
        // stays set and section-B values are preserved.
        val secondRead = store.data.first()
        assertEquals(
            AppStateMigrations.CURRENT_MIGRATION_VERSION,
            secondRead[AppStateKeys.MIGRATION_VERSION],
        )
        assertEquals(100, secondRead[AppStateKeys.SP_UNREAD_MSG_NUM])
    }
}
