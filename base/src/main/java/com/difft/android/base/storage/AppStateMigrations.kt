@file:Suppress("DEPRECATION") 
                              

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











internal object AppStateMigrations {

    
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
        
        copyIntIfPresent(sp, mut, "SP_BYC_DOMAINS_TIME", AppStateKeys.SP_UNREAD_MSG_NUM)
        copyIntIfPresent(sp, mut, "call_feedback_random_threshold", AppStateKeys.CALL_FEEDBACK_RANDOM_THRESHOLD)
        copyIntIfPresent(sp, mut, "call_count", AppStateKeys.CALL_COUNT)
        copyIntIfPresent(sp, mut, "keyboard_height_portrait", AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT)
        copyIntIfPresent(sp, mut, "keyboard_height_landscape", AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE)
        
        copyStringIfPresent(sp, mut, "SP_DENOISE_MODE", AppStateKeys.SP_DENOISE_MODE)
        copyStringIfPresent(sp, mut, "sp_speed_test_success_host", AppStateKeys.SP_KEY_BEST_HOST)
        copyStringIfPresent(sp, mut, "gray_map_json", AppStateKeys.GRAY_MAP_JSON)
        copyStringIfPresent(sp, mut, "SP_KEY_CRITICAL_ALERT_INFOS", AppStateKeys.SP_KEY_CRITICAL_ALERT_INFOS)
        
        copyLongIfPresent(sp, mut, "call_last_feedback_reset_time", AppStateKeys.CALL_LAST_FEEDBACK_RESET_TIME)
        
        copyBooleanIfPresent(sp, mut, "call_feedback_has_triggered", AppStateKeys.CALL_FEEDBACK_HAS_TRIGGERED)
        L.i { "[Storage][app_state][Migration1] migrated sp_chative_account keys" }
        return mut.toPreferences()
    }

    











    private fun migrateFromDefaultPrefs(context: Context): DataMigration<Preferences> {
        val defaultPrefsName = "${context.packageName}_preferences"
        return object : DataMigration<Preferences> {
            private val sp by lazy {
                
                context.getSharedPreferences(defaultPrefsName, Context.MODE_PRIVATE)
            }

            override suspend fun shouldMigrate(currentData: Preferences): Boolean {
                val portraitMissing = !currentData.contains(AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT)
                val landscapeMissing = !currentData.contains(AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE)
                if (!portraitMissing && !landscapeMissing) return false
                
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
            legacy.floatingWindowPermissionTipsShowedVersion?.let {
                mut[AppStateKeys.FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION] = it
            }
            mut[AppStateKeys.NOTIFICATION_CONTENT_DISPLAY_TYPE] = legacy.notificationContentDisplayType
            mut[AppStateKeys.GLOBAL_NOTIFICATION] = legacy.globalNotification
            mut[AppStateKeys.HAS_SHOWN_CONFIDENTIAL_TIP] = legacy.hasShownConfidentialTip
            mut[AppStateKeys.IMAGE_EDITOR_MARKER_PERCENTAGE] = legacy.imageEditorMarkerPercentage
            mut[AppStateKeys.IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE] = legacy.imageEditorHighlighterPercentage
            mut[AppStateKeys.IMAGE_EDITOR_BLUR_PERCENTAGE] = legacy.imageEditorBlurPercentage

            
            mut[AppStateKeys.MIGRATION_VERSION] = CURRENT_MIGRATION_VERSION
            L.i { "[Storage][app_state][Migration4] projected UX fields and stamped MIGRATION_VERSION" }
            return mut.toPreferences()
        }

        
        override suspend fun cleanUp() = Unit

        




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
