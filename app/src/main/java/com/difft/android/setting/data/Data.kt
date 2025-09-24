package com.difft.android.setting.data

data class GetProfileResponse(
    val searchByPhone: Int,
    val searchByEmail: Int,
    val emailMasked: String?,
    val phoneMasked: String?,
    val passkeysSwitch: Int
)

//    "searchFlag":0 // 0x1: phone, 0x2: email, 0x3: phone&email
data class SetStatusRequestBody(
    // 0：表示关闭，1 表示打开
    val searchByPhone: Int,
    val searchByEmail: Int
)

data class CheckUpdateBody(
    val version: String
)

data class CheckUpdateResponse(
    val update: Boolean,
    val force: Boolean,
    val url: String,
    val notes: String,
    val updateWithApk: Boolean,
    val apkHash: String
)

data class ResetPasscodeRequestBody(
    val passcode: String, //hash:salt
    val screenLockTimeout: Int,//in seconds 0:instant -1:invalid
)
