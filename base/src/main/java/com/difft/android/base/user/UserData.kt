package com.difft.android.base.user

import androidx.appcompat.app.AppCompatDelegate
import com.difft.android.base.utils.TextSizeUtil

/**
 * UserData layout mirrors the underlying storage layout (issue #725):
 *
 * ```
 * ┌─ secure_user.pb            Encrypted (Tink AEAD) — 17 auth/identity + proxy fields
 * │                            Field order matches UserAuthData @ProtoNumber tags 1..15, 17..18 (16 is the migration marker).
 * └─ app_state.preferences_pb  Plain key-value — UX preferences + module-owned state
 *                              Grouped by domain (UX / lifecycle / lock / sync /
 *                              notification / message-service / image-editor / call / network).
 * ```
 *
 * **Adding a new field**: find the group whose storage location + module ownership
 * matches, append at the end of that group. Then update `UserDataFieldRouter`
 * (`diff` + `applyAppStateChangeToSnapshot` + `compose`) and add a default to
 * `AppStateDefaults` if applicable. Don't reorder existing fields — they have
 * stable positions matching `@ProtoNumber` tags / `AppStateKeys` mapping.
 */
data class UserData(
    // ═══════════════════════════════════════════════════════════════
    // [secure_user.pb] Auth / identity + self-hosted proxy — encrypted Tink AEAD
    //   Field order matches UserAuthData @ProtoNumber tags 1..15, 17..18
    // ═══════════════════════════════════════════════════════════════
    var account: String? = null,
    var baseAuth: String? = null,
    var microToken: String? = null,
    var email: String? = null,
    var phoneNumber: String? = null,
    var customUid: String? = null,
    var contactRequestStatus: String? = null,
    var passcode: String? = null, //passcode hash:salt
    var pattern: String? = null, //手势图案 hash:salt
    var signalingKey: String? = null, //通道加密
    // 端到端消息加密相关
    var aciIdentityPublicKey: String? = null, // ACI身份公钥
    var aciIdentityPrivateKey: String? = null, // ACI身份私钥
    var aciIdentityOldPublicKey: String? = null, // 旧ACI身份公钥
    var aciIdentityOldPrivateKey: String? = null, // 旧ACI身份私钥
    var aciIdentityKeyGenTime: Long = 0, // ACI身份密钥生成时间
    var proxyShareLink: String? = null, // self-hosted proxy share link (ytp://config?d=...). Contains TURN secret when set.
    var proxyEnabled: Boolean = false,   // self-hosted proxy on/off toggle. Orthogonal to whether proxyShareLink parses successfully.
    var proxyProtectCallIp: Boolean = false, // route call/meeting network through the proxy only when ON. Gated by proxyEnabled; meaningless while the proxy is off.
    // Favorites (GIF) account-level secret. favKey decrypts the server-held favorites blob, so it
    // is account-level secret material (like baseAuth/identity keys) and lives in the encrypted
    // secure_user half — decoupled from WCDB health, so a DB corruption-recovery reset does not
    // lose it (the blob is re-pullable from the server with the surviving key).
    var favKey: String? = null,          // Base64 NO_WRAP of the raw 32-byte AES-256 favKey
    var favKeyId: String? = null,        // favKey fingerprint (presence = "a key is stored")
    var favKeyVersion: Int = 0,          // monotonic version gate (server-assigned)

    // ═══════════════════════════════════════════════════════════════
    // [app_state.preferences_pb] Plain key-value, grouped by domain
    // ═══════════════════════════════════════════════════════════════

    // — App-wide UX preferences —
    var theme: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, //AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM   AppCompatDelegate.MODE_NIGHT_NO   AppCompatDelegate.MODE_NIGHT_YES
    var textSize: Int = TextSizeUtil.TEXT_SIZE_DEFAULT,
    var saveToPhotos: Boolean = false, //是否开启自动保存到相册功能
    var voicePlaybackSpeed: Float = 1.0f, //语音消息播放速度 (1.0x, 1.5x, 2.0x)
    var mostUseEmojis: String? = null,
    var dualPaneRatio: Float = -1f, //双栏布局 list_pane 占可用宽度的比例(0.0–1.0),-1 表示未自定义,走默认逻辑

    // — App lifecycle —
    var lastUseTime: Long = 0,
    var lastCheckUpdateTime: Long = 0, // 上次检查更新的时间

    // — App lock (passcode + pattern) —
    var passcodeTimeout: Int = 300, //默认值300s
    var passcodeAttempts: Int = 0, //密码已经尝试的次数
    var patternShowPath: Boolean = true, //是否显示手势路径
    var patternAttempts: Int = 0, //手势已经尝试的次数

    // — Contact sync state —
    var searchByCustomUid: Int = 0,
    var directoryVersionForContactors: Int = 0,
    var syncedContactsV4: Boolean = false, //是否已经同步过联系人
    var syncedGroupAndMembers: Boolean = false, //是否已经同步过群和成员信息

    // — Notification preferences + unread —
    var notificationContentDisplayType: Int = NotificationContentDisplayType.NAME_AND_CONTENT.value, //通知显示类型
    var globalNotification: Int = GlobalNotificationType.ALL.value, //全局通知开关类型
    var checkNotificationPermission: String? = null, //上次检查通知权限的版本
    var unreadMsgNum: Int = 0, //未读消息数(原 SP_BYC_DOMAINS_TIME)

    // — Message-service preferences (chat module) —
    var keepAliveEnabled: Boolean = false, // Service保活机制是否启用
    var autoStartMessageService: Boolean = true, // 是否允许自动开启消息后台连接服务（默认true，用户手动关闭后为false）
    var messageServiceTipsShowedVersion: String? = null, //上次提示开启消息后台连接服务的版本
    var floatingWindowPermissionTipsShowedVersion: String? = null, //上次提示开启悬浮窗权限的版本

    // — Chat misc —
    var hasShownConfidentialTip: Boolean = false, // 机密消息首次使用提示

    // — Image editor (brush-width preferences, 0–100 percentage; 0 = slider leftmost) —
    var imageEditorMarkerPercentage: Int = 0,
    var imageEditorHighlighterPercentage: Int = 0,
    var imageEditorBlurPercentage: Int = 0,

    // — Call module —
    var callVoiceChangerPreset: String = "original", //通话全局变音偏好 (VoicePreset.sdkKey: original/goddess/uncle)
    var denoiseMode: String? = null, //通话降噪模式(原 SP_DENOISE_MODE)
    var criticalAlertInfos: String? = null, //Critical Alert 通知节流状态 JSON(原 SP_KEY_CRITICAL_ALERT_INFOS)
    var callLastFeedbackResetTime: Long = 0, //上次重置通话反馈采样的时间
    var callFeedbackRandomThreshold: Int = 3, //通话反馈随机采样阈值
    var callFeedbackHasTriggered: Boolean = false, //本周期反馈是否已触发

    // — Network module —
    var bestHost: String? = null, //speed-test 选出的最佳 host(原 sp_speed_test_success_host)
    var grayMapJson: String? = null, //feature gray map JSON
)
