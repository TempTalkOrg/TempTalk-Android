package com.difft.android.login.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

data class CodeResponse(
    val inviter: String?,
    val account: String,
    val vcode: String
)
@Parcelize
data class EmailVerifyData(
    val status: Int,
    



    val nextStep: Int,
    
    val invitationCode: String?,
    
    val account: String?,
    
    val verificationCode: String?,
    val requirePin: Boolean,
    
    val transferable: Int,
    val requirePasscode: Boolean,
    val passcodeSalt: String?,
    val screenLockTimeout: Int,
    
    val tokens: Map<String, String>? = null,
) : Parcelable

data class AccountData(
    val newUser: Boolean,
)

data class RenewIdentityKeyRequestBody(
    val identityKey: String,
    val registrationId: Int,
    val newSign: String,
    val oldSign: String?,
)


data class GenerateNonceCodeResponse(
    val code: String?
)

data class BindAccountResponse(
    val nonce: String?
)

data class NonceInfo(
    val uuid: String?,
    val timestamp: Long,
    val version: Int,
    val difficulty: Int
)

data class NonceInfoRequestBody(
    val uuid: String?,
    val solution: String?
)
