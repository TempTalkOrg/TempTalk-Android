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
    /** Only present at `nextStep == 0` on the plain contract; null on the transfer-token reply. */
    val invitationCode: String?,
    /** Absent when the reply carries a transfer token instead (see [tokens]) on older servers. */
    val account: String?,
    /** Absent when the reply carries a transfer token instead of a one-time code. */
    val verificationCode: String?,
    val requirePin: Boolean,
    /** 1 when the account may defer device registration behind a transfer token. */
    val transferable: Int,
    val requirePasscode: Boolean,
    val passcodeSalt: String?,
    val screenLockTimeout: Int,
    /** `logintoken` → the renew token handed out for `supportTransfer = 1`; `tdtoken` is legacy. */
    val tokens: Map<String, String>? = null,
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
