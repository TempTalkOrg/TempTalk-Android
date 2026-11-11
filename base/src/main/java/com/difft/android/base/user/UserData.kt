package com.difft.android.base.user

import androidx.appcompat.app.AppCompatDelegate
import com.difft.android.base.utils.TextSizeUtil


















data class UserData(
    
    
    
    
    var account: String? = null,
    var baseAuth: String? = null,
    var microToken: String? = null,
    var email: String? = null,
    var phoneNumber: String? = null,
    var customUid: String? = null,
    var contactRequestStatus: String? = null,
    var passcode: String? = null, 
    var pattern: String? = null, 
    var signalingKey: String? = null, 
    
    var aciIdentityPublicKey: String? = null,
    var aciIdentityPrivateKey: String? = null,
    var aciIdentityOldPublicKey: String? = null,
    var aciIdentityOldPrivateKey: String? = null,
    var aciIdentityKeyGenTime: Long = 0,
    var proxyShareLink: String? = null, 
    var proxyEnabled: Boolean = false,   
    var proxyProtectCallIp: Boolean = false, 
    
    
    
    
    var favKey: String? = null,          
    var favKeyId: String? = null,        
    var favKeyVersion: Int = 0,          

    
    
    

    
    var theme: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, 
    var textSize: Int = TextSizeUtil.TEXT_SIZE_DEFAULT,
    var chatTheme: Int = 0, 
    var saveToPhotos: Boolean = false,
    var voicePlaybackSpeed: Float = 1.0f, 
    var mostUseEmojis: String? = null,
    var dualPaneRatio: Float = -1f, 
    var dualPaneListCollapsed: Boolean = false, 
    var legacyMigrationGuideShown: Boolean = false, 

    
    
    var migrationHandoffId: String = "",
    
    var migrationHandoffCompletedId: String = "",
    
    var migrationHandoffDoneAt: Long = 0,

    
    var lastUseTime: Long = 0,
    var lastCheckUpdateTime: Long = 0,

    
    var passcodeTimeout: Int = 300, 
    var passcodeAttempts: Int = 0,
    var passcodeAutoMisses: Int = 0, 
    var patternShowPath: Boolean = true,
    var patternAttempts: Int = 0,

    
    var searchByCustomUid: Int = 0,
    var directoryVersionForContactors: Int = 0,
    var syncedContactsV5: Boolean = false, 
    var syncedGroupAndMembers: Boolean = false,

    
    var notificationContentDisplayType: Int = NotificationContentDisplayType.NAME_AND_CONTENT.value,
    var globalNotification: Int = GlobalNotificationType.ALL.value,
    var unreadMsgNum: Int = 0,

    
    var keepAliveEnabled: Boolean = false,
    var autoStartMessageService: Boolean = true,
    var floatingWindowPermissionTipsShowedVersion: String? = null, 

    
    var hasShownConfidentialTip: Boolean = false,

    
    var imageEditorMarkerPercentage: Int = 0,
    var imageEditorHighlighterPercentage: Int = 0,
    var imageEditorBlurPercentage: Int = 0,

    
    var callVoiceChangerPreset: String = "original", 
    var denoiseMode: String? = null,
    var criticalAlertInfos: String? = null, 
    var callLastFeedbackResetTime: Long = 0,
    var callFeedbackRandomThreshold: Int = 3,
    var callFeedbackHasTriggered: Boolean = false, 
    
    
    var callMicPermissionRequested: Boolean = false, 
    var callCameraPermissionRequested: Boolean = false, 

    
    var bestHost: String? = null, 
    var grayMapJson: String? = null, 
)
