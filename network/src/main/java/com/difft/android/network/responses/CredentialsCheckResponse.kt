package com.difft.android.network.responses

data class CredentialsCheckResponse(
    val status: Int,
    val nextStep: Int,
    val invitationCode: String,
    val account: String,
    val verificationCode: String,
    val requirePin: Boolean,

    val requirePasscode: Boolean,
    val passcodeSalt: String?,
    val screenLockTimeout: Int,
)