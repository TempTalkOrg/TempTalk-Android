package com.difft.android.base.storage

import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.difft.android.base.utils.TextSizeUtil






object AppStateKeys {

    
    
    
    
    
    val SEARCH_BY_CUSTOM_UID = intPreferencesKey("search_by_custom_uid")
    val DIRECTORY_VERSION_FOR_CONTACTORS = intPreferencesKey("directory_version_for_contactors")
    val MOST_USE_EMOJIS = stringPreferencesKey("most_use_emojis")
    val SYNCED_CONTACTS_V5 = booleanPreferencesKey("synced_contacts_v5")
    val SYNCED_GROUP_AND_MEMBERS = booleanPreferencesKey("synced_group_and_members")
    val PASSCODE_TIMEOUT = intPreferencesKey("passcode_timeout")
    val PASSCODE_ATTEMPTS = intPreferencesKey("passcode_attempts")
    val PASSCODE_AUTO_MISSES = intPreferencesKey("passcode_auto_misses")
    val PATTERN_SHOW_PATH = booleanPreferencesKey("pattern_show_path")
    val PATTERN_ATTEMPTS = intPreferencesKey("pattern_attempts")
    val LAST_USE_TIME = longPreferencesKey("last_use_time")              
    val THEME = intPreferencesKey("theme")
    val TEXT_SIZE = intPreferencesKey("text_size")
    val CHAT_THEME = intPreferencesKey("chat_theme")
    val LAST_CHECK_UPDATE_TIME = longPreferencesKey("last_check_update_time")
    val SAVE_TO_PHOTOS = booleanPreferencesKey("save_to_photos")
    val VOICE_PLAYBACK_SPEED = floatPreferencesKey("voice_playback_speed")
    val DUAL_PANE_RATIO = floatPreferencesKey("dual_pane_ratio")
    val DUAL_PANE_LIST_COLLAPSED = booleanPreferencesKey("dual_pane_list_collapsed")
    val LEGACY_MIGRATION_GUIDE_SHOWN = booleanPreferencesKey("legacy_migration_guide_shown")
    val CALL_VOICE_CHANGER_PRESET = stringPreferencesKey("call_voice_changer_preset")
    val CALL_MIC_PERMISSION_REQUESTED = booleanPreferencesKey("call_mic_permission_requested")
    val CALL_CAMERA_PERMISSION_REQUESTED = booleanPreferencesKey("call_camera_permission_requested")
    val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
    val AUTO_START_MESSAGE_SERVICE = booleanPreferencesKey("auto_start_message_service")
    val FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION =
        stringPreferencesKey("floating_window_permission_tips_showed_version")
    val NOTIFICATION_CONTENT_DISPLAY_TYPE = intPreferencesKey("notification_content_display_type")
    val GLOBAL_NOTIFICATION = intPreferencesKey("global_notification")
    val HAS_SHOWN_CONFIDENTIAL_TIP = booleanPreferencesKey("has_shown_confidential_tip")
    val IMAGE_EDITOR_MARKER_PERCENTAGE = intPreferencesKey("image_editor_marker_percentage")    
    val IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE = intPreferencesKey("image_editor_highlighter_percentage")
    val IMAGE_EDITOR_BLUR_PERCENTAGE = intPreferencesKey("image_editor_blur_percentage")

    
    
    
    
    val SP_UNREAD_MSG_NUM = intPreferencesKey("SP_BYC_DOMAINS_TIME")
    val SP_DENOISE_MODE = stringPreferencesKey("SP_DENOISE_MODE")
    val SP_KEY_BEST_HOST = stringPreferencesKey("sp_speed_test_success_host")
    val GRAY_MAP_JSON = stringPreferencesKey("gray_map_json")
    val SP_KEY_CRITICAL_ALERT_INFOS = stringPreferencesKey("SP_KEY_CRITICAL_ALERT_INFOS")
    val CALL_LAST_FEEDBACK_RESET_TIME = longPreferencesKey("call_last_feedback_reset_time")
    val CALL_FEEDBACK_RANDOM_THRESHOLD = intPreferencesKey("call_feedback_random_threshold")
    val CALL_FEEDBACK_HAS_TRIGGERED = booleanPreferencesKey("call_feedback_has_triggered")
    val CALL_COUNT = intPreferencesKey("call_count")

    
    
    
    
    
    
    val KEY_KEYBOARD_HEIGHT_PORTRAIT = intPreferencesKey("keyboard_height_portrait")
    val KEY_KEYBOARD_HEIGHT_LANDSCAPE = intPreferencesKey("keyboard_height_landscape")

    
    
    
    internal val MIGRATION_VERSION = intPreferencesKey("__app_state_migration_version")

    
    
    
    
    val LAST_SEEN_EXIT_TS = longPreferencesKey("last_seen_exit_ts")

    
    
    
    val BG_HEALTH_EXIT_COUNTS = stringPreferencesKey("bg_health_exit_counts")

    
    val BG_HEALTH_FGS_FAILS = stringPreferencesKey("bg_health_fgs_fails")

    
    val BG_HEALTH_LAST_REPORT_TS = longPreferencesKey("bg_health_last_report_ts")

    
    val BG_HEALTH_EVENT_COUNTS = stringPreferencesKey("bg_health_event_counts")

    
    val BG_HEALTH_SAMPLE_SLOT = intPreferencesKey("bg_health_sample_slot")

    
    val BG_HEALTH_DAILY = stringPreferencesKey("bg_health_daily")

    
    val BG_LATE_DELIVERY_DAYS = stringPreferencesKey("bg_late_delivery_days")

    
    val BG_STALL_DAYS = stringPreferencesKey("bg_stall_days")

    
    val BG_KEEP_ALIVE_ON_DAY = longPreferencesKey("bg_keep_alive_on_day")

    
    val BG_FCM_FAIL_SINCE = longPreferencesKey("bg_fcm_fail_since")

    
    val BG_FCM_STATE = stringPreferencesKey("bg_fcm_state")

    
    
    
    
    
    val SERVER_TIME_OFFSET = longPreferencesKey("server_time_offset")
    val LAST_KNOWN_SERVER_TIME = longPreferencesKey("last_known_server_time")

    
    
    
    
    val ARCHIVE_TOMBSTONE_SENTINEL_MIGRATED = booleanPreferencesKey("archive_tombstone_sentinel_migrated")

    
    
    
    
    
    
    val ATTACHMENT_MIGRATION_VERSION = intPreferencesKey("attachment_migration_version")

    
    
    
    
    val ATTACHMENT_MIGRATION_STAGE = intPreferencesKey("attachment_migration_stage")
    val ATTACHMENT_MIGRATION_WATERMARK = intPreferencesKey("attachment_migration_watermark")

    
    
    
    
    
    val ATTACHMENT_MIGRATION_FILE_ATTEMPTS = intPreferencesKey("attachment_migration_file_attempts")

    
    
    
    
    
    val ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS = intPreferencesKey("attachment_migration_sweep_attempts")

    
    
    
    
    val ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS = intPreferencesKey("attachment_migration_normal_attempts")

    
    
    
    
    
    
    val MIGRATION_HANDOFF_ID = stringPreferencesKey("migration_handoff_id")
    val MIGRATION_HANDOFF_COMPLETED_ID = stringPreferencesKey("migration_handoff_completed_id")
    val MIGRATION_HANDOFF_DONE_AT = longPreferencesKey("migration_handoff_done_at")

    
    val MIGRATION_PENDING_VERDICT = stringPreferencesKey("migration_pending_verdict")

    



    val MIGRATION_MESSAGE_PAUSE = stringPreferencesKey("migration_message_pause")

    



    val UNTAGGED_SEND_JOBS_SWEPT = booleanPreferencesKey("untagged_send_jobs_swept")

    
    
    
    
    
    
    
    
    
    
    
    val TRANSLATE_MODEL_DOWNLOAD_STATE = stringPreferencesKey("translate_model_download_state")

    

    
    val NOTIFICATION_CHANNEL_VERSION = intPreferencesKey("notification_channel_version")

    
    val CRITICAL_ALERT_BYPASS_ATTEMPT_VERSION = stringPreferencesKey("critical_alert_bypass_attempt_version")

    
    val NOTIFICATION_CHANNEL_UNKNOWN_LOGGED = booleanPreferencesKey("notification_channel_unknown_logged")

    

    
    val STARTUP_PROMPT_STATE = stringPreferencesKey("startup_prompt_state")

    
    
    
    
    val NOTIF_TEST_BANNER = stringPreferencesKey("notif_test_banner")
    val NOTIF_TEST_LOCKSCREEN = stringPreferencesKey("notif_test_lockscreen")
    val NOTIF_TEST_CALL = stringPreferencesKey("notif_test_call")
    val NOTIF_TEST_CALL_TICKET = stringPreferencesKey("notif_test_call_ticket_v1")

    
    val NOTIF_CATEGORY_SEEN = stringSetPreferencesKey("notif_category_seen")

    

    
    val GROUP_FOCUS_STATE = stringPreferencesKey("group_focus_state")

    




    val FOCUS_PIN_OPT_OUT_GID = stringPreferencesKey("focus_pin_opt_out_gid")

    
    val FOCUS_INVALID_DIALOG_HANDLED_EPISODE = stringPreferencesKey("focus_invalid_dialog_handled_episode")

    
    val FOCUS_INVALID_STRIP_DISMISSED_EPISODE = stringPreferencesKey("focus_invalid_strip_dismissed_episode")

    




    val FOCUS_GROUP_NAME_GID = stringPreferencesKey("focus_group_name_gid")
    val FOCUS_GROUP_NAME = stringPreferencesKey("focus_group_name")

    
    val FOCUS_DELETED_ROW_GIDS = stringPreferencesKey("focus_deleted_row_gids")

}








object AppStateDefaults {
    const val SEARCH_BY_CUSTOM_UID = 0
    const val DIRECTORY_VERSION_FOR_CONTACTORS = 0
    
    const val MOST_USE_EMOJIS = ""
    const val SYNCED_CONTACTS_V5 = false
    const val SYNCED_GROUP_AND_MEMBERS = false
    const val PASSCODE_TIMEOUT = 300
    const val PASSCODE_ATTEMPTS = 0
    const val PASSCODE_AUTO_MISSES = 0
    const val PATTERN_SHOW_PATH = true
    const val PATTERN_ATTEMPTS = 0
    const val LAST_USE_TIME = 0L
    val THEME = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    val TEXT_SIZE = TextSizeUtil.TEXT_SIZE_DEFAULT
    const val CHAT_THEME = 0
    const val LAST_CHECK_UPDATE_TIME = 0L
    const val SAVE_TO_PHOTOS = false
    const val VOICE_PLAYBACK_SPEED = 1.0f
    
    const val DUAL_PANE_RATIO = -1f
    const val DUAL_PANE_LIST_COLLAPSED = false
    const val LEGACY_MIGRATION_GUIDE_SHOWN = false
    const val CALL_VOICE_CHANGER_PRESET = "original"
    const val CALL_MIC_PERMISSION_REQUESTED = false
    const val CALL_CAMERA_PERMISSION_REQUESTED = false
    const val KEEP_ALIVE_ENABLED = false
    const val AUTO_START_MESSAGE_SERVICE = true
    
    const val FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION = ""
    const val NOTIFICATION_CONTENT_DISPLAY_TYPE = 0
    const val GLOBAL_NOTIFICATION = 0
    const val HAS_SHOWN_CONFIDENTIAL_TIP = false
    const val IMAGE_EDITOR_MARKER_PERCENTAGE = 0
    const val IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE = 0
    const val IMAGE_EDITOR_BLUR_PERCENTAGE = 0

    
    const val SP_UNREAD_MSG_NUM = 0
    const val SP_DENOISE_MODE = ""
    const val SP_KEY_BEST_HOST = ""
    const val GRAY_MAP_JSON = ""
    const val SP_KEY_CRITICAL_ALERT_INFOS = ""
    const val CALL_LAST_FEEDBACK_RESET_TIME = 0L
    const val CALL_FEEDBACK_RANDOM_THRESHOLD = 3
    const val CALL_FEEDBACK_HAS_TRIGGERED = false
    const val CALL_COUNT = 0

    
    const val KEY_KEYBOARD_HEIGHT_PORTRAIT = 0
    const val KEY_KEYBOARD_HEIGHT_LANDSCAPE = 0

    
    const val SERVER_TIME_OFFSET = 0L
    const val LAST_KNOWN_SERVER_TIME = 0L

    
    const val ARCHIVE_TOMBSTONE_SENTINEL_MIGRATED = false

    
    const val ATTACHMENT_MIGRATION_VERSION = 0
    const val ATTACHMENT_MIGRATION_STAGE = 0
    const val ATTACHMENT_MIGRATION_WATERMARK = 0
    const val ATTACHMENT_MIGRATION_FILE_ATTEMPTS = 0
    const val ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS = 0
    const val ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS = 0

    
    const val MIGRATION_HANDOFF_ID = ""
    const val MIGRATION_HANDOFF_COMPLETED_ID = ""
    const val MIGRATION_HANDOFF_DONE_AT = 0L
    const val UNTAGGED_SEND_JOBS_SWEPT = false

    
    const val TRANSLATE_MODEL_DOWNLOAD_STATE = ""

    
    const val NOTIFICATION_CHANNEL_VERSION = 0
    const val CRITICAL_ALERT_BYPASS_ATTEMPT_VERSION = ""
    const val NOTIFICATION_CHANNEL_UNKNOWN_LOGGED = false

    
    const val STARTUP_PROMPT_STATE = ""

    
    const val GROUP_FOCUS_STATE = ""
}
