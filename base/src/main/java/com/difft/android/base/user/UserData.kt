package com.difft.android.base.user

import androidx.appcompat.app.AppCompatDelegate
import com.difft.android.base.utils.TextSizeUtil

data class UserData(
    var account: String? = null,
    var password: String? = null,
    var baseAuth: String? = null,
    var microToken: String? = null,
    var email: String? = null,
    var phoneNumber: String? = null,
    var contactRequestStatus: String? = null,
    var directoryVersionForContactors: Int = 0,
    var mostUseEmojis: String? = null,
    var syncedContacts: Boolean = false,//是否已经同步过联系人
    var syncedGroupAndMembers: Boolean = false,//是否已经同步过群和成员信息
    var passcode: String? = null, //hash:salt
    var passcodeTimeout: Int = 300,//默认值300s
    var passcodeAttempts: Int = 0,//密码已经尝试的次数
    var lastUseTime: Long = 0,
    var checkIgnoreBatteryOptimizations: String? = null,
    var previousSuccessConnectedChatWebsocketHost: String? = null,
    var theme: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,   //AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM   AppCompatDelegate.MODE_NIGHT_NO   AppCompatDelegate.MODE_NIGHT_YES
    var textSize: Int = TextSizeUtil.TEXT_SIZE_DEFAULT,
    var lastCheckUpdateTime: Long = 0, // 上次检查更新的时间
    var fcmEnable: Boolean = false, // FCM是否注册成功
    var saveToPhotos: Boolean = false, //是否开启自动保存到相册功能
    var migratedReadInfo: Boolean = false, // 是否已经迁移过ReadInfo数据
    var autoStartMessageService: Boolean = true, //自动开启消息后台连接服务
    var messageServiceTipsShowedVersion: String? = null, //上次提示开启消息后台连接服务的版本

    //通道加密
    var signalingKey: String? = null,
    //端到端消息加密相关
    var aciIdentityPublicKey: String? = null, // ACI身份公钥
    var aciIdentityPrivateKey: String? = null, // ACI身份私钥
    var aciIdentityOldPublicKey: String? = null, // 旧ACI身份公钥
    var aciIdentityOldPrivateKey: String? = null, // 旧ACI身份私钥
    var aciIdentityKeyGenTime: Long = 0, // ACI身份密钥生成时间

    var notificationContentDisplayType: Int = NotificationContentDisplayType.NAME_AND_CONTENT.value, //通知显示类型
    var globalNotification: Int = GlobalNotificationType.ALL.value, //全局通知开关类型
)