package com.difft.android.base.storage

import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.difft.android.base.utils.TextSizeUtil

/**
 * Typed key catalog for the plain `app_state.preferences_pb` DataStore (issue #725, Task 5).
 *
 * Holds UX state + DB-recovery flags consolidated from multiple legacy SP files:
 *  - 26 UX fields carved out of the legacy 43-field `UserData` blob (Section A).
 *  - 9 keys migrated from `sp_chative_account` SharedPreferences (Section B).
 *  - 2 keyboard-height keys (Section C — historically split across `sp_chative_account`
 *    and `${packageName}_preferences`; `sp_chative_account` wins per design §4.3).
 *  - 1 migration bookkeeping marker (Section D, private to migration code).
 *
 * **Key-string preservation**: legacy key literals (`"SP_BYC_DOMAINS_TIME"`,
 * `"sp_speed_test_success_host"`, etc.) are kept verbatim
 * so `SharedPreferencesMigration` lambdas can move them across without rewriting.
 * Any rename here forfeits the legacy values — only rename in a follow-up cleanup
 * task with an explicit secondary migration.
 *
 * No encryption — none of these fields are auth material or PII.
 */
object AppStateKeys {

    // ---------- Section A: UX fields carved out of legacy `UserData` blob (26 keys) ----------
    //
    // The legacy `UserData` Gson blob lived in `secure_prefs` EncryptedSP under
    // `SHARED_PREFERENCES_KEY_USERDATA`. `UserDataUxFieldsMigration` reads it once on
    // first cold start and projects these 26 fields into the DataStore.
    val SEARCH_BY_CUSTOM_UID = intPreferencesKey("search_by_custom_uid")
    val DIRECTORY_VERSION_FOR_CONTACTORS = intPreferencesKey("directory_version_for_contactors")
    val MOST_USE_EMOJIS = stringPreferencesKey("most_use_emojis")
    val SYNCED_CONTACTS_V5 = booleanPreferencesKey("synced_contacts_v5")
    val SYNCED_GROUP_AND_MEMBERS = booleanPreferencesKey("synced_group_and_members")
    val PASSCODE_TIMEOUT = intPreferencesKey("passcode_timeout")
    val PASSCODE_ATTEMPTS = intPreferencesKey("passcode_attempts")
    val PATTERN_SHOW_PATH = booleanPreferencesKey("pattern_show_path")
    val PATTERN_ATTEMPTS = intPreferencesKey("pattern_attempts")
    val LAST_USE_TIME = longPreferencesKey("last_use_time")              // HOT WRITE — flushed via PendingLastUseTime
    val THEME = intPreferencesKey("theme")
    val TEXT_SIZE = intPreferencesKey("text_size")
    val LAST_CHECK_UPDATE_TIME = longPreferencesKey("last_check_update_time")
    val SAVE_TO_PHOTOS = booleanPreferencesKey("save_to_photos")
    val VOICE_PLAYBACK_SPEED = floatPreferencesKey("voice_playback_speed")
    val DUAL_PANE_RATIO = floatPreferencesKey("dual_pane_ratio")
    val DUAL_PANE_LIST_COLLAPSED = booleanPreferencesKey("dual_pane_list_collapsed")
    val CALL_VOICE_CHANGER_PRESET = stringPreferencesKey("call_voice_changer_preset")
    val CALL_MIC_PERMISSION_REQUESTED = booleanPreferencesKey("call_mic_permission_requested")
    val CALL_CAMERA_PERMISSION_REQUESTED = booleanPreferencesKey("call_camera_permission_requested")
    val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
    val AUTO_START_MESSAGE_SERVICE = booleanPreferencesKey("auto_start_message_service")
    val MESSAGE_SERVICE_TIPS_SHOWED_VERSION = stringPreferencesKey("message_service_tips_showed_version")
    val FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION =
        stringPreferencesKey("floating_window_permission_tips_showed_version")
    val NOTIFICATION_CONTENT_DISPLAY_TYPE = intPreferencesKey("notification_content_display_type")
    val GLOBAL_NOTIFICATION = intPreferencesKey("global_notification")
    val CHECK_NOTIFICATION_PERMISSION = stringPreferencesKey("check_notification_permission")
    val HAS_SHOWN_CONFIDENTIAL_TIP = booleanPreferencesKey("has_shown_confidential_tip")
    val IMAGE_EDITOR_MARKER_PERCENTAGE = intPreferencesKey("image_editor_marker_percentage")    // HOT WRITE — onStopTrackingTouch
    val IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE = intPreferencesKey("image_editor_highlighter_percentage")
    val IMAGE_EDITOR_BLUR_PERCENTAGE = intPreferencesKey("image_editor_blur_percentage")

    // ---------- Section B: 9 keys migrated from `sp_chative_account` SharedPreferences ----------
    //
    // Legacy key strings preserved verbatim so `SharedPreferencesMigration` carries values across.
    // - `SP_UNREAD_MSG_NUM` uses literal `"SP_BYC_DOMAINS_TIME"` for historical reasons
    //   (the constant name was repurposed without renaming the key in `SharedPrefsUtil`).
    val SP_UNREAD_MSG_NUM = intPreferencesKey("SP_BYC_DOMAINS_TIME")
    val SP_DENOISE_MODE = stringPreferencesKey("SP_DENOISE_MODE")
    val SP_KEY_BEST_HOST = stringPreferencesKey("sp_speed_test_success_host")
    val GRAY_MAP_JSON = stringPreferencesKey("gray_map_json")
    val SP_KEY_CRITICAL_ALERT_INFOS = stringPreferencesKey("SP_KEY_CRITICAL_ALERT_INFOS")
    val CALL_LAST_FEEDBACK_RESET_TIME = longPreferencesKey("call_last_feedback_reset_time")
    val CALL_FEEDBACK_RANDOM_THRESHOLD = intPreferencesKey("call_feedback_random_threshold")
    val CALL_FEEDBACK_HAS_TRIGGERED = booleanPreferencesKey("call_feedback_has_triggered")
    val CALL_COUNT = intPreferencesKey("call_count")

    // ---------- Section C: 2 keyboard-height keys (merged from two writers) ----------
    //
    // Historically the Kotlin path (`InsetAwareConstraintLayout`) wrote to `sp_chative_account`
    // and the Java path (`KeyboardAwareLinearLayout`) wrote to `${packageName}_preferences`.
    // Both legacy SP files carry the same key names; migration order picks `sp_chative_account`
    // first (wins) per design §4.3.
    val KEY_KEYBOARD_HEIGHT_PORTRAIT = intPreferencesKey("keyboard_height_portrait")
    val KEY_KEYBOARD_HEIGHT_LANDSCAPE = intPreferencesKey("keyboard_height_landscape")

    // ---------- Section D: Migration bookkeeping ----------
    //
    // Stamped as the LAST write inside `UserDataUxFieldsMigration.migrate()` (migration 4
    // in the chain). Once present, migrations 1–4 short-circuit on the next cold start.
    // `internal` because no consumer should be reading the marker outside migration code.
    internal val MIGRATION_VERSION = intPreferencesKey("__app_state_migration_version")

    // ---------- Section E: Monitoring (#971) ----------
    //
    // Newest ApplicationExitInfo timestamp already reported to Crashlytics. Used to dedup the
    // startup freeze probe so each real process exit is reported exactly once.
    val LAST_SEEN_EXIT_TS = longPreferencesKey("last_seen_exit_ts")

    // ---------- Section F: Trusted-time (ServerTimeProvider) ----------
    //
    // Cold-start fallback for the process-level trusted clock. Non-sensitive, non-PII — plain
    // app_state per issue #725 (SharedPreferences is Tink-keyset-only). Written throttled by
    // ServerTimeProvider (|Δoffset| > 1s); read once async at startup into its @Volatile fields.
    val SERVER_TIME_OFFSET = longPreferencesKey("server_time_offset")
    val LAST_KNOWN_SERVER_TIME = longPreferencesKey("last_known_server_time")

    // ---------- Section G: WCDB data-migration bookkeeping ----------
    //
    // One-time archive-tombstone sentinel re-anchor (WCDBUpdateService). Set only after the
    // migration completes, so a failed run retries on the next start.
    val ARCHIVE_TOMBSTONE_SENTINEL_MIGRATED = booleanPreferencesKey("archive_tombstone_sentinel_migrated")

    // ---------- Section H: Attachment address migration bookkeeping (#1178) ----------
    //
    // Highest migration version whose stages have all completed — the same versioned-marker shape as
    // `LegacyPlaintextAttachmentMigration`'s KEY_VERSION and the `syncedContactsVn` contact refresh:
    // a later wave raises the target version and the module replays only the stages that version
    // adds. Version 1 = forwarded attachments moved to their per-copy directories.
    val ATTACHMENT_MIGRATION_VERSION = intPreferencesKey("attachment_migration_version")

    // In-flight progress of the version currently being migrated, cleared when it completes: the
    // last finished stage, and the last attachment row (databaseId) whose file was processed, so an
    // interrupted run resumes instead of rescanning. All three are cleared with the rest of
    // app_state on logout, which is correct — the files are gone by then too.
    val ATTACHMENT_MIGRATION_STAGE = intPreferencesKey("attachment_migration_stage")
    val ATTACHMENT_MIGRATION_WATERMARK = intPreferencesKey("attachment_migration_watermark")

    // Completed file-migration passes (stage 2). A row whose file cannot be copied (a persistently
    // full disk, a stale directory occupying the target name) would otherwise keep the stage
    // unfinished forever, re-running the full table scan on every cold start and never stamping.
    // After MAX attempts the stage reports done despite the failures; the row is not abandoned, it
    // is materialized on demand by the per-row rescue path the next time the user opens it.
    val ATTACHMENT_MIGRATION_FILE_ATTEMPTS = intPreferencesKey("attachment_migration_file_attempts")

    // Completed sweep passes over the legacy directories (stage 3). The sweep can only ever delete —
    // a directory it keeps N runs in a row is permanently unverifiable garbage (a nested dir, a
    // stray entry from a row deleted pre-upgrade), so after MAX attempts the migration stamps its
    // version with the residue left in place instead of re-walking the attachment root on every
    // launch forever.
    val ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS = intPreferencesKey("attachment_migration_sweep_attempts")

    // Completed normal-attachment migration passes (stage 4). Same give-up budget as the file stage
    // above, and deliberately a SEPARATE counter: the two stages belong to different migration
    // versions and only the version stamp clears either, so sharing one key would let a device that
    // already gave up in stage 2 abandon stage 4's rows on their very first pass.
    val ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS = intPreferencesKey("attachment_migration_normal_attempts")
}

/**
 * Default values for [AppStateKeys] entries. Callers use these as the fallback
 * argument when a key is absent from the DataStore (e.g. fresh install before
 * the migration runs, or value was never written).
 *
 * Mirrors the prior `UserData` initializer defaults so reading `app_state` before
 * migration produces the same observable behavior as the old `UserData` getter.
 */
object AppStateDefaults {
    const val SEARCH_BY_CUSTOM_UID = 0
    const val DIRECTORY_VERSION_FOR_CONTACTORS = 0
    /** Nullable-string sentinel: callers use `getString(KEY).takeIf { it.isNotEmpty() }`. */
    const val MOST_USE_EMOJIS = ""
    const val SYNCED_CONTACTS_V5 = false
    const val SYNCED_GROUP_AND_MEMBERS = false
    const val PASSCODE_TIMEOUT = 300
    const val PASSCODE_ATTEMPTS = 0
    const val PATTERN_SHOW_PATH = true
    const val PATTERN_ATTEMPTS = 0
    const val LAST_USE_TIME = 0L
    val THEME = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    val TEXT_SIZE = TextSizeUtil.TEXT_SIZE_DEFAULT
    const val LAST_CHECK_UPDATE_TIME = 0L
    const val SAVE_TO_PHOTOS = false
    const val VOICE_PLAYBACK_SPEED = 1.0f
    /** Sentinel -1f = no user override; UI falls back to mode default (mirrors DualPaneRatioUtil.NO_OVERRIDE). */
    const val DUAL_PANE_RATIO = -1f
    const val DUAL_PANE_LIST_COLLAPSED = false
    const val CALL_VOICE_CHANGER_PRESET = "original"
    const val CALL_MIC_PERMISSION_REQUESTED = false
    const val CALL_CAMERA_PERMISSION_REQUESTED = false
    const val KEEP_ALIVE_ENABLED = false
    const val AUTO_START_MESSAGE_SERVICE = true
    /** Nullable-string sentinel — see [MOST_USE_EMOJIS]. */
    const val MESSAGE_SERVICE_TIPS_SHOWED_VERSION = ""
    const val FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION = ""
    const val NOTIFICATION_CONTENT_DISPLAY_TYPE = 0
    const val GLOBAL_NOTIFICATION = 0
    const val CHECK_NOTIFICATION_PERMISSION = ""
    const val HAS_SHOWN_CONFIDENTIAL_TIP = false
    const val IMAGE_EDITOR_MARKER_PERCENTAGE = 0
    const val IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE = 0
    const val IMAGE_EDITOR_BLUR_PERCENTAGE = 0

    // Section B defaults
    const val SP_UNREAD_MSG_NUM = 0
    const val SP_DENOISE_MODE = ""
    const val SP_KEY_BEST_HOST = ""
    const val GRAY_MAP_JSON = ""
    const val SP_KEY_CRITICAL_ALERT_INFOS = ""
    const val CALL_LAST_FEEDBACK_RESET_TIME = 0L
    const val CALL_FEEDBACK_RANDOM_THRESHOLD = 3
    const val CALL_FEEDBACK_HAS_TRIGGERED = false
    const val CALL_COUNT = 0

    // Section C defaults
    const val KEY_KEYBOARD_HEIGHT_PORTRAIT = 0
    const val KEY_KEYBOARD_HEIGHT_LANDSCAPE = 0

    // Section F defaults — 0L means "no offset / never seen a server time" (collapses to L3 wall clock).
    const val SERVER_TIME_OFFSET = 0L
    const val LAST_KNOWN_SERVER_TIME = 0L

    // Section G defaults
    const val ARCHIVE_TOMBSTONE_SENTINEL_MIGRATED = false

    // Section H defaults — 0 = nothing migrated yet (fresh install and pre-migration installs alike).
    const val ATTACHMENT_MIGRATION_VERSION = 0
    const val ATTACHMENT_MIGRATION_STAGE = 0
    const val ATTACHMENT_MIGRATION_WATERMARK = 0
    const val ATTACHMENT_MIGRATION_FILE_ATTEMPTS = 0
    const val ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS = 0
    const val ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS = 0
}
