@file:Suppress("DEPRECATION") // androidx.security.crypto.* is deprecated; intentional one-shot
                              // reader path during the issue #725 migration window.

package com.difft.android.base.storage

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataMigration
import androidx.datastore.migrations.SharedPreferencesMigration
import androidx.datastore.migrations.SharedPreferencesView
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.globalServices

/**
 * Migration chain for the `app_state.preferences_pb` DataStore (issue #725, Task 5).
 *
 * **Three migrations, in strict order**:
 *   1. [migrateFromSpChativeAccount]   — 11 keys from `sp_chative_account` (Section B + 2 keyboard).
 *   2. [migrateFromDefaultPrefs]       — 2 keyboard keys from `${packageName}_preferences`
 *      + zombie-key cleanup in `cleanUp()`. **Does NOT stamp `MIGRATION_VERSION`.**
 *   3. [UserDataUxFieldsMigration]     — 26 UX fields from the encrypted legacy
 *      `secure_prefs` Gson blob. Stamps `AppStateKeys.MIGRATION_VERSION` as the
 *      **LAST write inside `migrate()`** (atomic with the 26 field projections).
 *
 * **Why the stamp lives in the last migration, not its predecessor's `cleanUp()`**:
 *   - `DataMigration.cleanUp()` has no DataStore handle — it cannot perform `edit { ... }`.
 *   - Stamping inside `migrate()` of the LAST migration guarantees all migrations
 *     completed before `shouldMigrate` returns false on next cold start.
 *   - The marker write is atomic with the 26 field projections (single DataStore commit) —
 *     process kill between fields and marker is impossible.
 *
 * **Keyboard-height precedence**: migration 1 reads from `sp_chative_account` first.
 * Migration 2 (default-prefs) only fills the keyboard keys if absent (uses `shouldMigrate`
 * short-circuit via `keysToMigrate` so already-present keys are not overwritten).
 *
 * **Idempotency**:
 *   - Migrations 1–2 use `SharedPreferencesMigration` whose internal `shouldMigrate`
 *     short-circuits when all `keysToMigrate` are already present in DataStore.
 *   - The last migration short-circuits on `MIGRATION_VERSION` presence.
 *   - Once stamped, none of the migrations re-run on subsequent cold starts.
 */
internal object AppStateMigrations {

    /** Bump to force re-projection of the legacy `UserData` blob (also update [UserDataUxFieldsMigration.shouldMigrate]). */
    internal const val CURRENT_MIGRATION_VERSION = 1

    private const val LEGACY_SP_CHATIVE_ACCOUNT = "sp_chative_account"
    private const val LEGACY_SP_SECURE_PREFS = "secure_prefs"
    private const val LEGACY_KEY_USERDATA =
        "com.difft.chative.base.user.SimpleUserManager\$Companion.SHARED_PREFERENCES_KEY_USERDATA"

    fun build(context: Context): List<DataMigration<Preferences>> = listOf(
        migrateFromSpChativeAccount(context),
        migrateFromDefaultPrefs(context),
        UserDataUxFieldsMigration(context),
    )

    /**
     * Migration 1 — copies 11 keys from `sp_chative_account` SharedPreferences.
     *
     * Each entry is typed by the corresponding [AppStateKeys] field. The migrate
     * lambda only reads keys actually present in the source SP (via `contains`),
     * so absent keys are not stamped with defaults.
     *
     * Keyboard-height keys win FROM HERE (per design §4.3 — `sp_chative_account`
     * is the Kotlin writer's file; default-prefs is only a fallback in migration 2).
     */
    private fun migrateFromSpChativeAccount(context: Context): DataMigration<Preferences> =
        SharedPreferencesMigration(
            context = context,
            sharedPreferencesName = LEGACY_SP_CHATIVE_ACCOUNT,
            keysToMigrate = setOf(
                "SP_BYC_DOMAINS_TIME",
                "SP_DENOISE_MODE",
                "sp_speed_test_success_host",
                "gray_map_json",
                "SP_KEY_CRITICAL_ALERT_INFOS",
                "call_last_feedback_reset_time",
                "call_feedback_random_threshold",
                "call_feedback_has_triggered",
                "call_count",
                "keyboard_height_portrait",
                "keyboard_height_landscape",
            ),
            migrate = ::projectSpChativeAccountKeys,
        )

    private suspend fun projectSpChativeAccountKeys(
        sp: SharedPreferencesView,
        current: Preferences,
    ): Preferences {
        val mut = current.toMutablePreferences()
        // Int keys
        copyIntIfPresent(sp, mut, "SP_BYC_DOMAINS_TIME", AppStateKeys.SP_UNREAD_MSG_NUM)
        copyIntIfPresent(sp, mut, "call_feedback_random_threshold", AppStateKeys.CALL_FEEDBACK_RANDOM_THRESHOLD)
        copyIntIfPresent(sp, mut, "call_count", AppStateKeys.CALL_COUNT)
        copyIntIfPresent(sp, mut, "keyboard_height_portrait", AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT)
        copyIntIfPresent(sp, mut, "keyboard_height_landscape", AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE)
        // String keys
        copyStringIfPresent(sp, mut, "SP_DENOISE_MODE", AppStateKeys.SP_DENOISE_MODE)
        copyStringIfPresent(sp, mut, "sp_speed_test_success_host", AppStateKeys.SP_KEY_BEST_HOST)
        copyStringIfPresent(sp, mut, "gray_map_json", AppStateKeys.GRAY_MAP_JSON)
        copyStringIfPresent(sp, mut, "SP_KEY_CRITICAL_ALERT_INFOS", AppStateKeys.SP_KEY_CRITICAL_ALERT_INFOS)
        // Long keys
        copyLongIfPresent(sp, mut, "call_last_feedback_reset_time", AppStateKeys.CALL_LAST_FEEDBACK_RESET_TIME)
        // Boolean keys
        copyBooleanIfPresent(sp, mut, "call_feedback_has_triggered", AppStateKeys.CALL_FEEDBACK_HAS_TRIGGERED)
        L.i { "[Storage][app_state][Migration1] migrated sp_chative_account keys" }
        return mut.toPreferences()
    }

    /**
     * Migration 2 — copies the 2 keyboard-height keys from
     * `${packageName}_preferences` (the SP file used by `PreferenceManager.getDefaultSharedPreferences`,
     * historically written by the Java `KeyboardAwareLinearLayout` path).
     *
     * **Conflict resolution**: migration 1 from `sp_chative_account` runs FIRST and wins.
     * `keysToMigrate` ensures this lambda only fires if the keys are absent from DataStore
     * (i.e. migration 1 already moved them, this is a no-op).
     *
     * Zombie cleanup lives in [cleanUp]. **Does NOT stamp `MIGRATION_VERSION`** — only
     * the last migration does.
     */
    private fun migrateFromDefaultPrefs(context: Context): DataMigration<Preferences> {
        val defaultPrefsName = "${context.packageName}_preferences"
        return object : DataMigration<Preferences> {
            private val sp by lazy {
                // Reads from the same file as PreferenceManager.getDefaultSharedPreferences(context)
                context.getSharedPreferences(defaultPrefsName, Context.MODE_PRIVATE)
            }

            override suspend fun shouldMigrate(currentData: Preferences): Boolean {
                val portraitMissing = !currentData.contains(AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT)
                val landscapeMissing = !currentData.contains(AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE)
                if (!portraitMissing && !landscapeMissing) return false
                // Only attempt if the legacy file actually carries at least one of the keys
                return sp.contains("keyboard_height_portrait") || sp.contains("keyboard_height_landscape")
            }

            override suspend fun migrate(currentData: Preferences): Preferences {
                val mut = currentData.toMutablePreferences()
                if (!mut.contains(AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT) &&
                    sp.contains("keyboard_height_portrait")
                ) {
                    mut[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT] =
                        sp.getInt("keyboard_height_portrait", 0)
                }
                if (!mut.contains(AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE) &&
                    sp.contains("keyboard_height_landscape")
                ) {
                    mut[AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE] =
                        sp.getInt("keyboard_height_landscape", 0)
                }
                L.i { "[Storage][app_state][Migration2] migrated keyboard-height keys from default-prefs" }
                return mut.toPreferences()
            }

            override suspend fun cleanUp() {
                // Drop migrated keys + zombie keys (T-1/T-2/T-3/T-4/L-1/L-2 per design §11)
                // from the legacy default-prefs file. We do NOT stamp MIGRATION_VERSION here
                // — that lives in the last migration's migrate().
                try {
                    sp.edit {
                        remove("keyboard_height_portrait")
                        remove("keyboard_height_landscape")
                        remove("pref_multi_device")
                        remove("pref_incognito_keyboard")
                        remove("pref_screen_security")
                        remove("pref_job_manager_version")
                        remove("pref_database_encrypted_secret")
                        remove("pref_database_unencrypted_secret")
                    }
                    L.i { "[Storage][app_state][Migration2] zombie-key cleanup complete" }
                } catch (e: Exception) {
                    L.w { "[Storage][app_state][Migration2] cleanUp failed: ${e.stackTraceToString()}" }
                }
            }
        }
    }

    // Each helper checks `sp.contains(legacyKey)` first so absent keys are not stamped with defaults.

    private fun copyIntIfPresent(
        sp: SharedPreferencesView,
        mut: MutablePreferences,
        legacyKey: String,
        target: Preferences.Key<Int>,
    ) {
        if (sp.contains(legacyKey)) {
            mut[target] = sp.getInt(legacyKey, 0)
        }
    }

    private fun copyLongIfPresent(
        sp: SharedPreferencesView,
        mut: MutablePreferences,
        legacyKey: String,
        target: Preferences.Key<Long>,
    ) {
        if (sp.contains(legacyKey)) {
            mut[target] = sp.getLong(legacyKey, 0L)
        }
    }

    private fun copyBooleanIfPresent(
        sp: SharedPreferencesView,
        mut: MutablePreferences,
        legacyKey: String,
        target: Preferences.Key<Boolean>,
    ) {
        if (sp.contains(legacyKey)) {
            mut[target] = sp.getBoolean(legacyKey, false)
        }
    }

    private fun copyStringIfPresent(
        sp: SharedPreferencesView,
        mut: MutablePreferences,
        legacyKey: String,
        target: Preferences.Key<String>,
    ) {
        if (sp.contains(legacyKey)) {
            sp.getString(legacyKey, null)?.let { mut[target] = it }
        }
    }

    /**
     * Migration 4 — projects 26 UX fields from the encrypted `secure_prefs` legacy Gson blob
     * (`UserData` JSON under `SHARED_PREFERENCES_KEY_USERDATA`) into the DataStore, then stamps
     * [AppStateKeys.MIGRATION_VERSION] as the last write so the migration chain short-circuits
     * on subsequent cold starts.
     *
     * Uses a custom [DataMigration] because the source is an encrypted SP JSON blob that
     * `SharedPreferencesMigration` cannot read. The marker write is atomic with the 26 field
     * projections. Any read failure stamps the marker on empty data so the retry loop breaks.
     */
    internal class UserDataUxFieldsMigration(
        private val context: Context,
    ) : DataMigration<Preferences> {

        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            (currentData[AppStateKeys.MIGRATION_VERSION] ?: 0) < CURRENT_MIGRATION_VERSION

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mut = currentData.toMutablePreferences()
            val legacy = readLegacyUserDataBlob()
            if (legacy == null) {
                mut[AppStateKeys.MIGRATION_VERSION] = CURRENT_MIGRATION_VERSION
                L.i { "[Storage][app_state][Migration4] no legacy blob; stamped MIGRATION_VERSION on fresh install / unreadable legacy" }
                return mut.toPreferences()
            }

            // Project the legacy UX fields. SYNCED_CONTACTS_V5 is intentionally NOT projected: the
            // legacy blob only carried the retired v4 flag, so legacy-SP upgraders must re-sync once
            // (absent v5 key → read-fallback false → one full re-pull that seeds publicAccountType).
            mut[AppStateKeys.SEARCH_BY_CUSTOM_UID] = legacy.searchByCustomUid
            mut[AppStateKeys.DIRECTORY_VERSION_FOR_CONTACTORS] = legacy.directoryVersionForContactors
            legacy.mostUseEmojis?.let { mut[AppStateKeys.MOST_USE_EMOJIS] = it }
            mut[AppStateKeys.SYNCED_GROUP_AND_MEMBERS] = legacy.syncedGroupAndMembers
            mut[AppStateKeys.PASSCODE_TIMEOUT] = legacy.passcodeTimeout
            mut[AppStateKeys.PASSCODE_ATTEMPTS] = legacy.passcodeAttempts
            mut[AppStateKeys.PATTERN_SHOW_PATH] = legacy.patternShowPath
            mut[AppStateKeys.PATTERN_ATTEMPTS] = legacy.patternAttempts
            mut[AppStateKeys.LAST_USE_TIME] = legacy.lastUseTime
            mut[AppStateKeys.THEME] = legacy.theme
            mut[AppStateKeys.TEXT_SIZE] = legacy.textSize
            mut[AppStateKeys.LAST_CHECK_UPDATE_TIME] = legacy.lastCheckUpdateTime
            mut[AppStateKeys.SAVE_TO_PHOTOS] = legacy.saveToPhotos
            mut[AppStateKeys.VOICE_PLAYBACK_SPEED] = legacy.voicePlaybackSpeed
            mut[AppStateKeys.CALL_VOICE_CHANGER_PRESET] = legacy.callVoiceChangerPreset
            mut[AppStateKeys.KEEP_ALIVE_ENABLED] = legacy.keepAliveEnabled
            mut[AppStateKeys.AUTO_START_MESSAGE_SERVICE] = legacy.autoStartMessageService
            legacy.messageServiceTipsShowedVersion?.let {
                mut[AppStateKeys.MESSAGE_SERVICE_TIPS_SHOWED_VERSION] = it
            }
            legacy.floatingWindowPermissionTipsShowedVersion?.let {
                mut[AppStateKeys.FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION] = it
            }
            mut[AppStateKeys.NOTIFICATION_CONTENT_DISPLAY_TYPE] = legacy.notificationContentDisplayType
            mut[AppStateKeys.GLOBAL_NOTIFICATION] = legacy.globalNotification
            legacy.checkNotificationPermission?.let {
                mut[AppStateKeys.CHECK_NOTIFICATION_PERMISSION] = it
            }
            mut[AppStateKeys.HAS_SHOWN_CONFIDENTIAL_TIP] = legacy.hasShownConfidentialTip
            mut[AppStateKeys.IMAGE_EDITOR_MARKER_PERCENTAGE] = legacy.imageEditorMarkerPercentage
            mut[AppStateKeys.IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE] = legacy.imageEditorHighlighterPercentage
            mut[AppStateKeys.IMAGE_EDITOR_BLUR_PERCENTAGE] = legacy.imageEditorBlurPercentage

            // LAST write — atomic with the 26 field writes above.
            mut[AppStateKeys.MIGRATION_VERSION] = CURRENT_MIGRATION_VERSION
            L.i { "[Storage][app_state][Migration4] projected UX fields and stamped MIGRATION_VERSION" }
            return mut.toPreferences()
        }

        /** No-op — legacy `secure_prefs` blob stays for the rollback retention window. */
        override suspend fun cleanUp() = Unit

        /**
         * Reads the legacy Gson-serialized [UserData] blob from `secure_prefs`.
         * Returns `null` on any failure (fresh install, Keystore reset, decrypt fail, malformed JSON)
         * so the caller stamps the marker and breaks any infinite-retry scenario.
         */
        private fun readLegacyUserDataBlob(): UserData? {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    context,
                    LEGACY_SP_SECURE_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                val json = prefs.getString(LEGACY_KEY_USERDATA, null) ?: return null
                globalServices.gson.fromJson(json, UserData::class.java)
            } catch (e: Throwable) {
                L.w {
                    "[Storage][app_state][Migration4] legacy SP read failed: " +
                        "${e.javaClass.simpleName} msg=${e.message}"
                }
                null
            }
        }
    }
}
