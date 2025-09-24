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
    /**
     * 0: *invitationCode* is available, to register a new account;
     * 1: *account* and *verificationCode* are available, to sign in an existing account.
     */
    val nextStep: Int,
    val invitationCode: String,
    val account: String,
    val verificationCode: String,
    val requirePin: Boolean,
    val transferable: Int,
    val requirePasscode: Boolean,
    val passcodeSalt: String?,
    val screenLockTimeout: Int
) : Parcelable

data class AccountData(
    val newUser: Boolean,
//    val account: String,
//    val basicAuth: String,
//    val name: String,
//    val signalingKey: String?,
//    val registrationId: Int,
//    val passcode: String?,
//    val screenLockTimeout: Int?
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
