package com.difft.android.base.storage.user

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.storage.schema.UserAuthDataMapper
import com.difft.android.base.user.UserData

/**
 * Pure-function field router for [UserData] writes (issue #725, Task 7).
 *
 * Decides which underlying DataStore receives the changed fields:
 *  - 15 auth fields  → `secure_user.pb` ([UserAuthData] via [UserAuthDataMapper]).
 *  - 26 UX fields    → `app_state.preferences_pb` (typed [Preferences.Key]).
 *  - `password` dead field was dropped in Task 1.
 *
 * **Single source of truth** for the 43→15+26 split. Used by:
 *  - [StorageBoundUserManagerImpl.writeAll] — diff old vs new, dispatch each
 *    changed field to its DataStore.
 *  - [StorageBoundUserManagerImpl.updateAppState] — apply a single typed
 *    change to the in-memory snapshot.
 *  - [StorageBoundUserManagerImpl.warmUp] — compose snapshot from two stores.
 *
 * Stateless. Pure. Trivially testable. No I/O, no Hilt, no state.
 */
internal object UserDataFieldRouter {

    /**
     * Single mutation to apply to a [MutablePreferences]. Encapsulates the
     * typed key+value so the diff result is self-describing.
     */
    sealed interface AppStateChange {
        fun applyTo(mut: MutablePreferences)

        data class IntChange(val key: Preferences.Key<Int>, val value: Int) : AppStateChange {
            override fun applyTo(mut: MutablePreferences) {
                mut[key] = value
            }
        }

        data class LongChange(val key: Preferences.Key<Long>, val value: Long) : AppStateChange {
            override fun applyTo(mut: MutablePreferences) {
                mut[key] = value
            }
        }

        data class BooleanChange(val key: Preferences.Key<Boolean>, val value: Boolean) : AppStateChange {
            override fun applyTo(mut: MutablePreferences) {
                mut[key] = value
            }
        }

        data class FloatChange(val key: Preferences.Key<Float>, val value: Float) : AppStateChange {
            override fun applyTo(mut: MutablePreferences) {
                mut[key] = value
            }
        }

        data class StringChange(val key: Preferences.Key<String>, val value: String) : AppStateChange {
            override fun applyTo(mut: MutablePreferences) {
                mut[key] = value
            }
        }
    }

    /**
     * Result of a diff — what the writer must persist.
     *
     * @property newAuth Non-null when any auth field changed; carries the full
     *   new [UserAuthData] ready to be written via `secureUserStore.updateData { newAuth }`.
     * @property appStateChanges One [AppStateChange] per changed UX field.
     *   Empty list = no UX write.
     */
    data class Diff(
        val newAuth: UserAuthData?,
        val appStateChanges: List<AppStateChange>,
    )

    /**
     * Diff [old] vs [new]. Null [old] is treated as defaults — every non-default
     * field in [new] counts as a change. Returns the dispatch plan.
     */
    fun diff(old: UserData?, new: UserData): Diff {
        val prev = old ?: UserData()

        // Auth half — compute UserAuthData projections once, compare.
        val oldAuth = UserAuthDataMapper.fromUserData(prev)
        val newAuth = UserAuthDataMapper.fromUserData(new)
        val authChanged = oldAuth != newAuth

        // UX half — accumulate per-field changes (26 fields).
        val ux = buildList<AppStateChange> {
            if (prev.searchByCustomUid != new.searchByCustomUid) {
                add(AppStateChange.IntChange(AppStateKeys.SEARCH_BY_CUSTOM_UID, new.searchByCustomUid))
            }
            if (prev.directoryVersionForContactors != new.directoryVersionForContactors) {
                add(AppStateChange.IntChange(AppStateKeys.DIRECTORY_VERSION_FOR_CONTACTORS, new.directoryVersionForContactors))
            }
            if (prev.mostUseEmojis != new.mostUseEmojis) {
                add(AppStateChange.StringChange(AppStateKeys.MOST_USE_EMOJIS, new.mostUseEmojis.orEmpty()))
            }
            if (prev.syncedContactsV4 != new.syncedContactsV4) {
                add(AppStateChange.BooleanChange(AppStateKeys.SYNCED_CONTACTS_V4, new.syncedContactsV4))
            }
            if (prev.syncedGroupAndMembers != new.syncedGroupAndMembers) {
                add(AppStateChange.BooleanChange(AppStateKeys.SYNCED_GROUP_AND_MEMBERS, new.syncedGroupAndMembers))
            }
            if (prev.passcodeTimeout != new.passcodeTimeout) {
                add(AppStateChange.IntChange(AppStateKeys.PASSCODE_TIMEOUT, new.passcodeTimeout))
            }
            if (prev.passcodeAttempts != new.passcodeAttempts) {
                add(AppStateChange.IntChange(AppStateKeys.PASSCODE_ATTEMPTS, new.passcodeAttempts))
            }
            if (prev.patternShowPath != new.patternShowPath) {
                add(AppStateChange.BooleanChange(AppStateKeys.PATTERN_SHOW_PATH, new.patternShowPath))
            }
            if (prev.patternAttempts != new.patternAttempts) {
                add(AppStateChange.IntChange(AppStateKeys.PATTERN_ATTEMPTS, new.patternAttempts))
            }
            if (prev.lastUseTime != new.lastUseTime) {
                add(AppStateChange.LongChange(AppStateKeys.LAST_USE_TIME, new.lastUseTime))
            }
            if (prev.theme != new.theme) {
                add(AppStateChange.IntChange(AppStateKeys.THEME, new.theme))
            }
            if (prev.textSize != new.textSize) {
                add(AppStateChange.IntChange(AppStateKeys.TEXT_SIZE, new.textSize))
            }
            if (prev.lastCheckUpdateTime != new.lastCheckUpdateTime) {
                add(AppStateChange.LongChange(AppStateKeys.LAST_CHECK_UPDATE_TIME, new.lastCheckUpdateTime))
            }
            if (prev.saveToPhotos != new.saveToPhotos) {
                add(AppStateChange.BooleanChange(AppStateKeys.SAVE_TO_PHOTOS, new.saveToPhotos))
            }
            if (prev.voicePlaybackSpeed != new.voicePlaybackSpeed) {
                add(AppStateChange.FloatChange(AppStateKeys.VOICE_PLAYBACK_SPEED, new.voicePlaybackSpeed))
            }
            if (prev.dualPaneRatio != new.dualPaneRatio) {
                add(AppStateChange.FloatChange(AppStateKeys.DUAL_PANE_RATIO, new.dualPaneRatio))
            }
            if (prev.callVoiceChangerPreset != new.callVoiceChangerPreset) {
                add(AppStateChange.StringChange(AppStateKeys.CALL_VOICE_CHANGER_PRESET, new.callVoiceChangerPreset))
            }
            if (prev.keepAliveEnabled != new.keepAliveEnabled) {
                add(AppStateChange.BooleanChange(AppStateKeys.KEEP_ALIVE_ENABLED, new.keepAliveEnabled))
            }
            if (prev.autoStartMessageService != new.autoStartMessageService) {
                add(AppStateChange.BooleanChange(AppStateKeys.AUTO_START_MESSAGE_SERVICE, new.autoStartMessageService))
            }
            if (prev.messageServiceTipsShowedVersion != new.messageServiceTipsShowedVersion) {
                add(AppStateChange.StringChange(AppStateKeys.MESSAGE_SERVICE_TIPS_SHOWED_VERSION, new.messageServiceTipsShowedVersion.orEmpty()))
            }
            if (prev.floatingWindowPermissionTipsShowedVersion != new.floatingWindowPermissionTipsShowedVersion) {
                add(AppStateChange.StringChange(AppStateKeys.FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION, new.floatingWindowPermissionTipsShowedVersion.orEmpty()))
            }
            if (prev.notificationContentDisplayType != new.notificationContentDisplayType) {
                add(AppStateChange.IntChange(AppStateKeys.NOTIFICATION_CONTENT_DISPLAY_TYPE, new.notificationContentDisplayType))
            }
            if (prev.globalNotification != new.globalNotification) {
                add(AppStateChange.IntChange(AppStateKeys.GLOBAL_NOTIFICATION, new.globalNotification))
            }
            if (prev.checkNotificationPermission != new.checkNotificationPermission) {
                add(AppStateChange.StringChange(AppStateKeys.CHECK_NOTIFICATION_PERMISSION, new.checkNotificationPermission.orEmpty()))
            }
            if (prev.hasShownConfidentialTip != new.hasShownConfidentialTip) {
                add(AppStateChange.BooleanChange(AppStateKeys.HAS_SHOWN_CONFIDENTIAL_TIP, new.hasShownConfidentialTip))
            }
            if (prev.imageEditorMarkerPercentage != new.imageEditorMarkerPercentage) {
                add(AppStateChange.IntChange(AppStateKeys.IMAGE_EDITOR_MARKER_PERCENTAGE, new.imageEditorMarkerPercentage))
            }
            if (prev.imageEditorHighlighterPercentage != new.imageEditorHighlighterPercentage) {
                add(AppStateChange.IntChange(AppStateKeys.IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE, new.imageEditorHighlighterPercentage))
            }
            if (prev.imageEditorBlurPercentage != new.imageEditorBlurPercentage) {
                add(AppStateChange.IntChange(AppStateKeys.IMAGE_EDITOR_BLUR_PERCENTAGE, new.imageEditorBlurPercentage))
            }
            // — ad-hoc fields formerly accessed via raw AppStateKeys (issue #725 unification step) —
            if (prev.unreadMsgNum != new.unreadMsgNum) {
                add(AppStateChange.IntChange(AppStateKeys.SP_UNREAD_MSG_NUM, new.unreadMsgNum))
            }
            if (prev.denoiseMode != new.denoiseMode) {
                add(AppStateChange.StringChange(AppStateKeys.SP_DENOISE_MODE, new.denoiseMode.orEmpty()))
            }
            if (prev.criticalAlertInfos != new.criticalAlertInfos) {
                add(AppStateChange.StringChange(AppStateKeys.SP_KEY_CRITICAL_ALERT_INFOS, new.criticalAlertInfos.orEmpty()))
            }
            if (prev.callLastFeedbackResetTime != new.callLastFeedbackResetTime) {
                add(AppStateChange.LongChange(AppStateKeys.CALL_LAST_FEEDBACK_RESET_TIME, new.callLastFeedbackResetTime))
            }
            if (prev.callFeedbackRandomThreshold != new.callFeedbackRandomThreshold) {
                add(AppStateChange.IntChange(AppStateKeys.CALL_FEEDBACK_RANDOM_THRESHOLD, new.callFeedbackRandomThreshold))
            }
            if (prev.callFeedbackHasTriggered != new.callFeedbackHasTriggered) {
                add(AppStateChange.BooleanChange(AppStateKeys.CALL_FEEDBACK_HAS_TRIGGERED, new.callFeedbackHasTriggered))
            }
            if (prev.bestHost != new.bestHost) {
                add(AppStateChange.StringChange(AppStateKeys.SP_KEY_BEST_HOST, new.bestHost.orEmpty()))
            }
            if (prev.grayMapJson != new.grayMapJson) {
                add(AppStateChange.StringChange(AppStateKeys.GRAY_MAP_JSON, new.grayMapJson.orEmpty()))
            }
        }

        return Diff(
            newAuth = if (authChanged) newAuth else null,
            appStateChanges = ux,
        )
    }

    /**
     * Apply a list of [AppStateChange]s to a [MutablePreferences]. Convenience
     * for [StorageBoundUserManagerImpl.writeAll]'s `appStateStore.edit { ... }`
     * lambda.
     */
    fun applyAppStateChanges(prefs: MutablePreferences, changes: List<AppStateChange>) {
        changes.forEach { it.applyTo(prefs) }
    }

    /**
     * Apply a single typed app_state change to the in-memory [UserData]
     * snapshot. Used by [StorageBoundUserManagerImpl.updateAppState].
     *
     * Returns [base] unchanged if [key] does not map to a `UserData` field
     * (e.g., keyboard heights, call counters, DB-recovery flags).
     *
     * The `@Suppress("UNCHECKED_CAST")` is safe because the typed
     * [Preferences.Key] system guarantees `T` matches the field type.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> applyAppStateChangeToSnapshot(base: UserData, key: Preferences.Key<T>, value: T): UserData =
        when (key) {
            AppStateKeys.SEARCH_BY_CUSTOM_UID -> base.copy(searchByCustomUid = value as Int)
            AppStateKeys.DIRECTORY_VERSION_FOR_CONTACTORS -> base.copy(directoryVersionForContactors = value as Int)
            AppStateKeys.MOST_USE_EMOJIS -> base.copy(mostUseEmojis = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.SYNCED_CONTACTS_V4 -> base.copy(syncedContactsV4 = value as Boolean)
            AppStateKeys.SYNCED_GROUP_AND_MEMBERS -> base.copy(syncedGroupAndMembers = value as Boolean)
            AppStateKeys.PASSCODE_TIMEOUT -> base.copy(passcodeTimeout = value as Int)
            AppStateKeys.PASSCODE_ATTEMPTS -> base.copy(passcodeAttempts = value as Int)
            AppStateKeys.PATTERN_SHOW_PATH -> base.copy(patternShowPath = value as Boolean)
            AppStateKeys.PATTERN_ATTEMPTS -> base.copy(patternAttempts = value as Int)
            AppStateKeys.LAST_USE_TIME -> base.copy(lastUseTime = value as Long)
            AppStateKeys.THEME -> base.copy(theme = value as Int)
            AppStateKeys.TEXT_SIZE -> base.copy(textSize = value as Int)
            AppStateKeys.LAST_CHECK_UPDATE_TIME -> base.copy(lastCheckUpdateTime = value as Long)
            AppStateKeys.SAVE_TO_PHOTOS -> base.copy(saveToPhotos = value as Boolean)
            AppStateKeys.VOICE_PLAYBACK_SPEED -> base.copy(voicePlaybackSpeed = value as Float)
            AppStateKeys.DUAL_PANE_RATIO -> base.copy(dualPaneRatio = value as Float)
            AppStateKeys.CALL_VOICE_CHANGER_PRESET -> base.copy(callVoiceChangerPreset = value as String)
            AppStateKeys.KEEP_ALIVE_ENABLED -> base.copy(keepAliveEnabled = value as Boolean)
            AppStateKeys.AUTO_START_MESSAGE_SERVICE -> base.copy(autoStartMessageService = value as Boolean)
            AppStateKeys.MESSAGE_SERVICE_TIPS_SHOWED_VERSION ->
                base.copy(messageServiceTipsShowedVersion = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION ->
                base.copy(floatingWindowPermissionTipsShowedVersion = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.NOTIFICATION_CONTENT_DISPLAY_TYPE -> base.copy(notificationContentDisplayType = value as Int)
            AppStateKeys.GLOBAL_NOTIFICATION -> base.copy(globalNotification = value as Int)
            AppStateKeys.CHECK_NOTIFICATION_PERMISSION ->
                base.copy(checkNotificationPermission = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.HAS_SHOWN_CONFIDENTIAL_TIP -> base.copy(hasShownConfidentialTip = value as Boolean)
            AppStateKeys.IMAGE_EDITOR_MARKER_PERCENTAGE -> base.copy(imageEditorMarkerPercentage = value as Int)
            AppStateKeys.IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE -> base.copy(imageEditorHighlighterPercentage = value as Int)
            AppStateKeys.IMAGE_EDITOR_BLUR_PERCENTAGE -> base.copy(imageEditorBlurPercentage = value as Int)
            // — ad-hoc fields lifted from raw AppStateKeys access (issue #725) —
            AppStateKeys.SP_UNREAD_MSG_NUM -> base.copy(unreadMsgNum = value as Int)
            AppStateKeys.SP_DENOISE_MODE ->
                base.copy(denoiseMode = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.SP_KEY_CRITICAL_ALERT_INFOS ->
                base.copy(criticalAlertInfos = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.CALL_LAST_FEEDBACK_RESET_TIME -> base.copy(callLastFeedbackResetTime = value as Long)
            AppStateKeys.CALL_FEEDBACK_RANDOM_THRESHOLD -> base.copy(callFeedbackRandomThreshold = value as Int)
            AppStateKeys.CALL_FEEDBACK_HAS_TRIGGERED -> base.copy(callFeedbackHasTriggered = value as Boolean)
            AppStateKeys.SP_KEY_BEST_HOST ->
                base.copy(bestHost = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.GRAY_MAP_JSON ->
                base.copy(grayMapJson = (value as String).takeIf { it.isNotEmpty() })
            else -> base   // key doesn't map to UserData (keyboard heights, DB-recovery, call_count)
        }

    /**
     * Compose a [UserData] snapshot from the auth half (DataStore) +
     * app_state half (DataStore). [fallback] supplies any UX fields that
     * aren't yet in [appState] (e.g., on fresh install the legacy SP may
     * still hold values during the v(N+1) window).
     */
    fun compose(
        auth: UserAuthData,
        appState: Preferences,
        fallback: UserData?,
    ): UserData {
        val base = fallback ?: UserData()
        return UserAuthDataMapper.toUserData(auth, base).copy(
            searchByCustomUid = appState[AppStateKeys.SEARCH_BY_CUSTOM_UID] ?: base.searchByCustomUid,
            directoryVersionForContactors = appState[AppStateKeys.DIRECTORY_VERSION_FOR_CONTACTORS] ?: base.directoryVersionForContactors,
            mostUseEmojis = appState[AppStateKeys.MOST_USE_EMOJIS]?.takeIf { it.isNotEmpty() } ?: base.mostUseEmojis,
            syncedContactsV4 = appState[AppStateKeys.SYNCED_CONTACTS_V4] ?: base.syncedContactsV4,
            syncedGroupAndMembers = appState[AppStateKeys.SYNCED_GROUP_AND_MEMBERS] ?: base.syncedGroupAndMembers,
            passcodeTimeout = appState[AppStateKeys.PASSCODE_TIMEOUT] ?: base.passcodeTimeout,
            passcodeAttempts = appState[AppStateKeys.PASSCODE_ATTEMPTS] ?: base.passcodeAttempts,
            patternShowPath = appState[AppStateKeys.PATTERN_SHOW_PATH] ?: base.patternShowPath,
            patternAttempts = appState[AppStateKeys.PATTERN_ATTEMPTS] ?: base.patternAttempts,
            lastUseTime = appState[AppStateKeys.LAST_USE_TIME] ?: base.lastUseTime,
            theme = appState[AppStateKeys.THEME] ?: base.theme,
            textSize = appState[AppStateKeys.TEXT_SIZE] ?: base.textSize,
            lastCheckUpdateTime = appState[AppStateKeys.LAST_CHECK_UPDATE_TIME] ?: base.lastCheckUpdateTime,
            saveToPhotos = appState[AppStateKeys.SAVE_TO_PHOTOS] ?: base.saveToPhotos,
            voicePlaybackSpeed = appState[AppStateKeys.VOICE_PLAYBACK_SPEED] ?: base.voicePlaybackSpeed,
            dualPaneRatio = appState[AppStateKeys.DUAL_PANE_RATIO] ?: base.dualPaneRatio,
            callVoiceChangerPreset = appState[AppStateKeys.CALL_VOICE_CHANGER_PRESET] ?: base.callVoiceChangerPreset,
            keepAliveEnabled = appState[AppStateKeys.KEEP_ALIVE_ENABLED] ?: base.keepAliveEnabled,
            autoStartMessageService = appState[AppStateKeys.AUTO_START_MESSAGE_SERVICE] ?: base.autoStartMessageService,
            messageServiceTipsShowedVersion = appState[AppStateKeys.MESSAGE_SERVICE_TIPS_SHOWED_VERSION]?.takeIf { it.isNotEmpty() }
                ?: base.messageServiceTipsShowedVersion,
            floatingWindowPermissionTipsShowedVersion = appState[AppStateKeys.FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION]?.takeIf { it.isNotEmpty() }
                ?: base.floatingWindowPermissionTipsShowedVersion,
            notificationContentDisplayType = appState[AppStateKeys.NOTIFICATION_CONTENT_DISPLAY_TYPE] ?: base.notificationContentDisplayType,
            globalNotification = appState[AppStateKeys.GLOBAL_NOTIFICATION] ?: base.globalNotification,
            checkNotificationPermission = appState[AppStateKeys.CHECK_NOTIFICATION_PERMISSION]?.takeIf { it.isNotEmpty() }
                ?: base.checkNotificationPermission,
            hasShownConfidentialTip = appState[AppStateKeys.HAS_SHOWN_CONFIDENTIAL_TIP] ?: base.hasShownConfidentialTip,
            imageEditorMarkerPercentage = appState[AppStateKeys.IMAGE_EDITOR_MARKER_PERCENTAGE] ?: base.imageEditorMarkerPercentage,
            imageEditorHighlighterPercentage = appState[AppStateKeys.IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE] ?: base.imageEditorHighlighterPercentage,
            imageEditorBlurPercentage = appState[AppStateKeys.IMAGE_EDITOR_BLUR_PERCENTAGE] ?: base.imageEditorBlurPercentage,
            // — ad-hoc fields lifted from raw AppStateKeys access (issue #725) —
            unreadMsgNum = appState[AppStateKeys.SP_UNREAD_MSG_NUM] ?: base.unreadMsgNum,
            denoiseMode = appState[AppStateKeys.SP_DENOISE_MODE]?.takeIf { it.isNotEmpty() } ?: base.denoiseMode,
            criticalAlertInfos = appState[AppStateKeys.SP_KEY_CRITICAL_ALERT_INFOS]?.takeIf { it.isNotEmpty() } ?: base.criticalAlertInfos,
            callLastFeedbackResetTime = appState[AppStateKeys.CALL_LAST_FEEDBACK_RESET_TIME] ?: base.callLastFeedbackResetTime,
            callFeedbackRandomThreshold = appState[AppStateKeys.CALL_FEEDBACK_RANDOM_THRESHOLD] ?: base.callFeedbackRandomThreshold,
            callFeedbackHasTriggered = appState[AppStateKeys.CALL_FEEDBACK_HAS_TRIGGERED] ?: base.callFeedbackHasTriggered,
            bestHost = appState[AppStateKeys.SP_KEY_BEST_HOST]?.takeIf { it.isNotEmpty() } ?: base.bestHost,
            grayMapJson = appState[AppStateKeys.GRAY_MAP_JSON]?.takeIf { it.isNotEmpty() } ?: base.grayMapJson,
        )
    }

}
