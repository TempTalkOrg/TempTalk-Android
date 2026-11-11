package com.difft.android.base.storage.user

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.storage.schema.UserAuthDataMapper
import com.difft.android.base.user.UserData






internal object UserDataFieldRouter {

    



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

    







    data class Diff(
        val newAuth: UserAuthData?,
        val appStateChanges: List<AppStateChange>,
    )

    



    fun diff(old: UserData?, new: UserData): Diff = project(old ?: UserData(), new, includeDefaults = false)

    
    fun snapshot(value: UserData): Diff = project(UserData(), value, includeDefaults = true)

    private fun project(prev: UserData, new: UserData, includeDefaults: Boolean): Diff {

        
        val oldAuth = UserAuthDataMapper.fromUserData(prev)
        val newAuth = UserAuthDataMapper.fromUserData(new)
        val authChanged = includeDefaults || oldAuth != newAuth

        val ux = buildList<AppStateChange> {
            appendAccountAndDisplayChanges(prev, new, includeDefaults)
            appendFeatureAndMigrationChanges(prev, new, includeDefaults)
        }

        return Diff(
            newAuth = if (authChanged) newAuth else null,
            appStateChanges = ux,
        )
    }

    private fun MutableList<AppStateChange>.appendAccountAndDisplayChanges(
        prev: UserData,
        new: UserData,
        includeDefaults: Boolean,
    ) {
        if (includeDefaults || prev.searchByCustomUid != new.searchByCustomUid) {
            add(AppStateChange.IntChange(AppStateKeys.SEARCH_BY_CUSTOM_UID, new.searchByCustomUid))
        }
        if (includeDefaults || prev.directoryVersionForContactors != new.directoryVersionForContactors) {
            add(AppStateChange.IntChange(AppStateKeys.DIRECTORY_VERSION_FOR_CONTACTORS, new.directoryVersionForContactors))
        }
        if (includeDefaults || prev.mostUseEmojis != new.mostUseEmojis) {
            add(AppStateChange.StringChange(AppStateKeys.MOST_USE_EMOJIS, new.mostUseEmojis.orEmpty()))
        }
        if (includeDefaults || prev.syncedContactsV5 != new.syncedContactsV5) {
            add(AppStateChange.BooleanChange(AppStateKeys.SYNCED_CONTACTS_V5, new.syncedContactsV5))
        }
        if (includeDefaults || prev.syncedGroupAndMembers != new.syncedGroupAndMembers) {
            add(AppStateChange.BooleanChange(AppStateKeys.SYNCED_GROUP_AND_MEMBERS, new.syncedGroupAndMembers))
        }
        if (includeDefaults || prev.passcodeTimeout != new.passcodeTimeout) {
            add(AppStateChange.IntChange(AppStateKeys.PASSCODE_TIMEOUT, new.passcodeTimeout))
        }
        if (includeDefaults || prev.passcodeAttempts != new.passcodeAttempts) {
            add(AppStateChange.IntChange(AppStateKeys.PASSCODE_ATTEMPTS, new.passcodeAttempts))
        }
        if (includeDefaults || prev.passcodeAutoMisses != new.passcodeAutoMisses) {
            add(AppStateChange.IntChange(AppStateKeys.PASSCODE_AUTO_MISSES, new.passcodeAutoMisses))
        }
        if (includeDefaults || prev.patternShowPath != new.patternShowPath) {
            add(AppStateChange.BooleanChange(AppStateKeys.PATTERN_SHOW_PATH, new.patternShowPath))
        }
        if (includeDefaults || prev.patternAttempts != new.patternAttempts) {
            add(AppStateChange.IntChange(AppStateKeys.PATTERN_ATTEMPTS, new.patternAttempts))
        }
        if (includeDefaults || prev.lastUseTime != new.lastUseTime) {
            add(AppStateChange.LongChange(AppStateKeys.LAST_USE_TIME, new.lastUseTime))
        }
        if (includeDefaults || prev.theme != new.theme) {
            add(AppStateChange.IntChange(AppStateKeys.THEME, new.theme))
        }
        if (includeDefaults || prev.textSize != new.textSize) {
            add(AppStateChange.IntChange(AppStateKeys.TEXT_SIZE, new.textSize))
        }
        if (includeDefaults || prev.chatTheme != new.chatTheme) {
            add(AppStateChange.IntChange(AppStateKeys.CHAT_THEME, new.chatTheme))
        }
        if (includeDefaults || prev.lastCheckUpdateTime != new.lastCheckUpdateTime) {
            add(AppStateChange.LongChange(AppStateKeys.LAST_CHECK_UPDATE_TIME, new.lastCheckUpdateTime))
        }
        if (includeDefaults || prev.saveToPhotos != new.saveToPhotos) {
            add(AppStateChange.BooleanChange(AppStateKeys.SAVE_TO_PHOTOS, new.saveToPhotos))
        }
        if (includeDefaults || prev.voicePlaybackSpeed != new.voicePlaybackSpeed) {
            add(AppStateChange.FloatChange(AppStateKeys.VOICE_PLAYBACK_SPEED, new.voicePlaybackSpeed))
        }
        if (includeDefaults || prev.dualPaneRatio != new.dualPaneRatio) {
            add(AppStateChange.FloatChange(AppStateKeys.DUAL_PANE_RATIO, new.dualPaneRatio))
        }
        if (includeDefaults || prev.dualPaneListCollapsed != new.dualPaneListCollapsed) {
            add(AppStateChange.BooleanChange(AppStateKeys.DUAL_PANE_LIST_COLLAPSED, new.dualPaneListCollapsed))
        }
    }

    private fun MutableList<AppStateChange>.appendFeatureAndMigrationChanges(
        prev: UserData,
        new: UserData,
        includeDefaults: Boolean,
    ) {
        if (includeDefaults || prev.legacyMigrationGuideShown != new.legacyMigrationGuideShown) {
            add(AppStateChange.BooleanChange(AppStateKeys.LEGACY_MIGRATION_GUIDE_SHOWN, new.legacyMigrationGuideShown))
        }
        if (includeDefaults || prev.migrationHandoffId != new.migrationHandoffId) {
            add(AppStateChange.StringChange(AppStateKeys.MIGRATION_HANDOFF_ID, new.migrationHandoffId))
        }
        if (includeDefaults || prev.migrationHandoffCompletedId != new.migrationHandoffCompletedId) {
            add(AppStateChange.StringChange(AppStateKeys.MIGRATION_HANDOFF_COMPLETED_ID, new.migrationHandoffCompletedId))
        }
        if (includeDefaults || prev.migrationHandoffDoneAt != new.migrationHandoffDoneAt) {
            add(AppStateChange.LongChange(AppStateKeys.MIGRATION_HANDOFF_DONE_AT, new.migrationHandoffDoneAt))
        }
        if (includeDefaults || prev.callVoiceChangerPreset != new.callVoiceChangerPreset) {
            add(AppStateChange.StringChange(AppStateKeys.CALL_VOICE_CHANGER_PRESET, new.callVoiceChangerPreset))
        }
        if (includeDefaults || prev.keepAliveEnabled != new.keepAliveEnabled) {
            add(AppStateChange.BooleanChange(AppStateKeys.KEEP_ALIVE_ENABLED, new.keepAliveEnabled))
        }
        if (includeDefaults || prev.autoStartMessageService != new.autoStartMessageService) {
            add(AppStateChange.BooleanChange(AppStateKeys.AUTO_START_MESSAGE_SERVICE, new.autoStartMessageService))
        }
        if (includeDefaults || prev.floatingWindowPermissionTipsShowedVersion != new.floatingWindowPermissionTipsShowedVersion) {
            add(AppStateChange.StringChange(AppStateKeys.FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION, new.floatingWindowPermissionTipsShowedVersion.orEmpty()))
        }
        if (includeDefaults || prev.notificationContentDisplayType != new.notificationContentDisplayType) {
            add(AppStateChange.IntChange(AppStateKeys.NOTIFICATION_CONTENT_DISPLAY_TYPE, new.notificationContentDisplayType))
        }
        if (includeDefaults || prev.globalNotification != new.globalNotification) {
            add(AppStateChange.IntChange(AppStateKeys.GLOBAL_NOTIFICATION, new.globalNotification))
        }
        if (includeDefaults || prev.hasShownConfidentialTip != new.hasShownConfidentialTip) {
            add(AppStateChange.BooleanChange(AppStateKeys.HAS_SHOWN_CONFIDENTIAL_TIP, new.hasShownConfidentialTip))
        }
        if (includeDefaults || prev.imageEditorMarkerPercentage != new.imageEditorMarkerPercentage) {
            add(AppStateChange.IntChange(AppStateKeys.IMAGE_EDITOR_MARKER_PERCENTAGE, new.imageEditorMarkerPercentage))
        }
        if (includeDefaults || prev.imageEditorHighlighterPercentage != new.imageEditorHighlighterPercentage) {
            add(AppStateChange.IntChange(AppStateKeys.IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE, new.imageEditorHighlighterPercentage))
        }
        if (includeDefaults || prev.imageEditorBlurPercentage != new.imageEditorBlurPercentage) {
            add(AppStateChange.IntChange(AppStateKeys.IMAGE_EDITOR_BLUR_PERCENTAGE, new.imageEditorBlurPercentage))
        }
        
        if (includeDefaults || prev.unreadMsgNum != new.unreadMsgNum) {
            add(AppStateChange.IntChange(AppStateKeys.SP_UNREAD_MSG_NUM, new.unreadMsgNum))
        }
        if (includeDefaults || prev.denoiseMode != new.denoiseMode) {
            add(AppStateChange.StringChange(AppStateKeys.SP_DENOISE_MODE, new.denoiseMode.orEmpty()))
        }
        if (includeDefaults || prev.criticalAlertInfos != new.criticalAlertInfos) {
            add(AppStateChange.StringChange(AppStateKeys.SP_KEY_CRITICAL_ALERT_INFOS, new.criticalAlertInfos.orEmpty()))
        }
        if (includeDefaults || prev.callLastFeedbackResetTime != new.callLastFeedbackResetTime) {
            add(AppStateChange.LongChange(AppStateKeys.CALL_LAST_FEEDBACK_RESET_TIME, new.callLastFeedbackResetTime))
        }
        if (includeDefaults || prev.callFeedbackRandomThreshold != new.callFeedbackRandomThreshold) {
            add(AppStateChange.IntChange(AppStateKeys.CALL_FEEDBACK_RANDOM_THRESHOLD, new.callFeedbackRandomThreshold))
        }
        if (includeDefaults || prev.callFeedbackHasTriggered != new.callFeedbackHasTriggered) {
            add(AppStateChange.BooleanChange(AppStateKeys.CALL_FEEDBACK_HAS_TRIGGERED, new.callFeedbackHasTriggered))
        }
        if (includeDefaults || prev.callMicPermissionRequested != new.callMicPermissionRequested) {
            add(AppStateChange.BooleanChange(AppStateKeys.CALL_MIC_PERMISSION_REQUESTED, new.callMicPermissionRequested))
        }
        if (includeDefaults || prev.callCameraPermissionRequested != new.callCameraPermissionRequested) {
            add(AppStateChange.BooleanChange(AppStateKeys.CALL_CAMERA_PERMISSION_REQUESTED, new.callCameraPermissionRequested))
        }
        if (includeDefaults || prev.bestHost != new.bestHost) {
            add(AppStateChange.StringChange(AppStateKeys.SP_KEY_BEST_HOST, new.bestHost.orEmpty()))
        }
        if (includeDefaults || prev.grayMapJson != new.grayMapJson) {
            add(AppStateChange.StringChange(AppStateKeys.GRAY_MAP_JSON, new.grayMapJson.orEmpty()))
        }
    }

    




    fun applyAppStateChanges(prefs: MutablePreferences, changes: List<AppStateChange>) {
        changes.forEach { it.applyTo(prefs) }
    }

    









    @Suppress("UNCHECKED_CAST")
    fun <T> applyAppStateChangeToSnapshot(base: UserData, key: Preferences.Key<T>, value: T): UserData =
        when (key) {
            AppStateKeys.SEARCH_BY_CUSTOM_UID -> base.copy(searchByCustomUid = value as Int)
            AppStateKeys.DIRECTORY_VERSION_FOR_CONTACTORS -> base.copy(directoryVersionForContactors = value as Int)
            AppStateKeys.MOST_USE_EMOJIS -> base.copy(mostUseEmojis = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.SYNCED_CONTACTS_V5 -> base.copy(syncedContactsV5 = value as Boolean)
            AppStateKeys.SYNCED_GROUP_AND_MEMBERS -> base.copy(syncedGroupAndMembers = value as Boolean)
            AppStateKeys.PASSCODE_TIMEOUT -> base.copy(passcodeTimeout = value as Int)
            AppStateKeys.PASSCODE_ATTEMPTS -> base.copy(passcodeAttempts = value as Int)
            AppStateKeys.PASSCODE_AUTO_MISSES -> base.copy(passcodeAutoMisses = value as Int)
            AppStateKeys.PATTERN_SHOW_PATH -> base.copy(patternShowPath = value as Boolean)
            AppStateKeys.PATTERN_ATTEMPTS -> base.copy(patternAttempts = value as Int)
            AppStateKeys.LAST_USE_TIME -> base.copy(lastUseTime = value as Long)
            AppStateKeys.THEME -> base.copy(theme = value as Int)
            AppStateKeys.TEXT_SIZE -> base.copy(textSize = value as Int)
            AppStateKeys.CHAT_THEME -> base.copy(chatTheme = value as Int)
            AppStateKeys.LAST_CHECK_UPDATE_TIME -> base.copy(lastCheckUpdateTime = value as Long)
            AppStateKeys.SAVE_TO_PHOTOS -> base.copy(saveToPhotos = value as Boolean)
            AppStateKeys.VOICE_PLAYBACK_SPEED -> base.copy(voicePlaybackSpeed = value as Float)
            AppStateKeys.DUAL_PANE_RATIO -> base.copy(dualPaneRatio = value as Float)
            AppStateKeys.DUAL_PANE_LIST_COLLAPSED -> base.copy(dualPaneListCollapsed = value as Boolean)
            AppStateKeys.LEGACY_MIGRATION_GUIDE_SHOWN -> base.copy(legacyMigrationGuideShown = value as Boolean)
            AppStateKeys.MIGRATION_HANDOFF_ID -> base.copy(migrationHandoffId = value as String)
            AppStateKeys.MIGRATION_HANDOFF_COMPLETED_ID -> base.copy(migrationHandoffCompletedId = value as String)
            AppStateKeys.MIGRATION_HANDOFF_DONE_AT -> base.copy(migrationHandoffDoneAt = value as Long)
            AppStateKeys.CALL_VOICE_CHANGER_PRESET -> base.copy(callVoiceChangerPreset = value as String)
            AppStateKeys.KEEP_ALIVE_ENABLED -> base.copy(keepAliveEnabled = value as Boolean)
            AppStateKeys.AUTO_START_MESSAGE_SERVICE -> base.copy(autoStartMessageService = value as Boolean)
            AppStateKeys.FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION ->
                base.copy(floatingWindowPermissionTipsShowedVersion = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.NOTIFICATION_CONTENT_DISPLAY_TYPE -> base.copy(notificationContentDisplayType = value as Int)
            AppStateKeys.GLOBAL_NOTIFICATION -> base.copy(globalNotification = value as Int)
            AppStateKeys.HAS_SHOWN_CONFIDENTIAL_TIP -> base.copy(hasShownConfidentialTip = value as Boolean)
            AppStateKeys.IMAGE_EDITOR_MARKER_PERCENTAGE -> base.copy(imageEditorMarkerPercentage = value as Int)
            AppStateKeys.IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE -> base.copy(imageEditorHighlighterPercentage = value as Int)
            AppStateKeys.IMAGE_EDITOR_BLUR_PERCENTAGE -> base.copy(imageEditorBlurPercentage = value as Int)
            
            AppStateKeys.SP_UNREAD_MSG_NUM -> base.copy(unreadMsgNum = value as Int)
            AppStateKeys.SP_DENOISE_MODE ->
                base.copy(denoiseMode = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.SP_KEY_CRITICAL_ALERT_INFOS ->
                base.copy(criticalAlertInfos = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.CALL_LAST_FEEDBACK_RESET_TIME -> base.copy(callLastFeedbackResetTime = value as Long)
            AppStateKeys.CALL_FEEDBACK_RANDOM_THRESHOLD -> base.copy(callFeedbackRandomThreshold = value as Int)
            AppStateKeys.CALL_FEEDBACK_HAS_TRIGGERED -> base.copy(callFeedbackHasTriggered = value as Boolean)
            AppStateKeys.CALL_MIC_PERMISSION_REQUESTED -> base.copy(callMicPermissionRequested = value as Boolean)
            AppStateKeys.CALL_CAMERA_PERMISSION_REQUESTED -> base.copy(callCameraPermissionRequested = value as Boolean)
            AppStateKeys.SP_KEY_BEST_HOST ->
                base.copy(bestHost = (value as String).takeIf { it.isNotEmpty() })
            AppStateKeys.GRAY_MAP_JSON ->
                base.copy(grayMapJson = (value as String).takeIf { it.isNotEmpty() })
            else -> base   
        }

    





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
            syncedContactsV5 = appState[AppStateKeys.SYNCED_CONTACTS_V5] ?: base.syncedContactsV5,
            syncedGroupAndMembers = appState[AppStateKeys.SYNCED_GROUP_AND_MEMBERS] ?: base.syncedGroupAndMembers,
            passcodeTimeout = appState[AppStateKeys.PASSCODE_TIMEOUT] ?: base.passcodeTimeout,
            passcodeAttempts = appState[AppStateKeys.PASSCODE_ATTEMPTS] ?: base.passcodeAttempts,
            passcodeAutoMisses = appState[AppStateKeys.PASSCODE_AUTO_MISSES] ?: base.passcodeAutoMisses,
            patternShowPath = appState[AppStateKeys.PATTERN_SHOW_PATH] ?: base.patternShowPath,
            patternAttempts = appState[AppStateKeys.PATTERN_ATTEMPTS] ?: base.patternAttempts,
            lastUseTime = appState[AppStateKeys.LAST_USE_TIME] ?: base.lastUseTime,
            theme = appState[AppStateKeys.THEME] ?: base.theme,
            textSize = appState[AppStateKeys.TEXT_SIZE] ?: base.textSize,
            chatTheme = appState[AppStateKeys.CHAT_THEME] ?: base.chatTheme,
            lastCheckUpdateTime = appState[AppStateKeys.LAST_CHECK_UPDATE_TIME] ?: base.lastCheckUpdateTime,
            saveToPhotos = appState[AppStateKeys.SAVE_TO_PHOTOS] ?: base.saveToPhotos,
            voicePlaybackSpeed = appState[AppStateKeys.VOICE_PLAYBACK_SPEED] ?: base.voicePlaybackSpeed,
            dualPaneRatio = appState[AppStateKeys.DUAL_PANE_RATIO] ?: base.dualPaneRatio,
            dualPaneListCollapsed = appState[AppStateKeys.DUAL_PANE_LIST_COLLAPSED] ?: base.dualPaneListCollapsed,
            legacyMigrationGuideShown = appState[AppStateKeys.LEGACY_MIGRATION_GUIDE_SHOWN] ?: base.legacyMigrationGuideShown,
            migrationHandoffId = appState[AppStateKeys.MIGRATION_HANDOFF_ID] ?: base.migrationHandoffId,
            migrationHandoffCompletedId = appState[AppStateKeys.MIGRATION_HANDOFF_COMPLETED_ID] ?: base.migrationHandoffCompletedId,
            migrationHandoffDoneAt = appState[AppStateKeys.MIGRATION_HANDOFF_DONE_AT] ?: base.migrationHandoffDoneAt,
            callVoiceChangerPreset = appState[AppStateKeys.CALL_VOICE_CHANGER_PRESET] ?: base.callVoiceChangerPreset,
            keepAliveEnabled = appState[AppStateKeys.KEEP_ALIVE_ENABLED] ?: base.keepAliveEnabled,
            autoStartMessageService = appState[AppStateKeys.AUTO_START_MESSAGE_SERVICE] ?: base.autoStartMessageService,
            floatingWindowPermissionTipsShowedVersion = appState[AppStateKeys.FLOATING_WINDOW_PERMISSION_TIPS_SHOWED_VERSION]?.takeIf { it.isNotEmpty() }
                ?: base.floatingWindowPermissionTipsShowedVersion,
            notificationContentDisplayType = appState[AppStateKeys.NOTIFICATION_CONTENT_DISPLAY_TYPE] ?: base.notificationContentDisplayType,
            globalNotification = appState[AppStateKeys.GLOBAL_NOTIFICATION] ?: base.globalNotification,
            hasShownConfidentialTip = appState[AppStateKeys.HAS_SHOWN_CONFIDENTIAL_TIP] ?: base.hasShownConfidentialTip,
            imageEditorMarkerPercentage = appState[AppStateKeys.IMAGE_EDITOR_MARKER_PERCENTAGE] ?: base.imageEditorMarkerPercentage,
            imageEditorHighlighterPercentage = appState[AppStateKeys.IMAGE_EDITOR_HIGHLIGHTER_PERCENTAGE] ?: base.imageEditorHighlighterPercentage,
            imageEditorBlurPercentage = appState[AppStateKeys.IMAGE_EDITOR_BLUR_PERCENTAGE] ?: base.imageEditorBlurPercentage,
            
            unreadMsgNum = appState[AppStateKeys.SP_UNREAD_MSG_NUM] ?: base.unreadMsgNum,
            denoiseMode = appState[AppStateKeys.SP_DENOISE_MODE]?.takeIf { it.isNotEmpty() } ?: base.denoiseMode,
            criticalAlertInfos = appState[AppStateKeys.SP_KEY_CRITICAL_ALERT_INFOS]?.takeIf { it.isNotEmpty() } ?: base.criticalAlertInfos,
            callLastFeedbackResetTime = appState[AppStateKeys.CALL_LAST_FEEDBACK_RESET_TIME] ?: base.callLastFeedbackResetTime,
            callFeedbackRandomThreshold = appState[AppStateKeys.CALL_FEEDBACK_RANDOM_THRESHOLD] ?: base.callFeedbackRandomThreshold,
            callFeedbackHasTriggered = appState[AppStateKeys.CALL_FEEDBACK_HAS_TRIGGERED] ?: base.callFeedbackHasTriggered,
            callMicPermissionRequested = appState[AppStateKeys.CALL_MIC_PERMISSION_REQUESTED] ?: base.callMicPermissionRequested,
            callCameraPermissionRequested = appState[AppStateKeys.CALL_CAMERA_PERMISSION_REQUESTED] ?: base.callCameraPermissionRequested,
            bestHost = appState[AppStateKeys.SP_KEY_BEST_HOST]?.takeIf { it.isNotEmpty() } ?: base.bestHost,
            grayMapJson = appState[AppStateKeys.GRAY_MAP_JSON]?.takeIf { it.isNotEmpty() } ?: base.grayMapJson,
        )
    }

}
