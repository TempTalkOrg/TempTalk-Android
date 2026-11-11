package com.difft.android.base.storage.schema

import com.difft.android.base.user.UserData







object UserAuthDataMapper {

    






    fun toUserData(auth: UserAuthData, base: UserData = UserData()): UserData = base.copy(
        account = auth.account.nullIfEmpty(),
        baseAuth = auth.baseAuth.nullIfEmpty(),
        microToken = auth.microToken.nullIfEmpty(),
        email = auth.email.nullIfEmpty(),
        phoneNumber = auth.phoneNumber.nullIfEmpty(),
        customUid = auth.customUid.nullIfEmpty(),
        contactRequestStatus = auth.contactRequestStatus.nullIfEmpty(),
        passcode = auth.passcode.nullIfEmpty(),
        pattern = auth.pattern.nullIfEmpty(),
        signalingKey = auth.signalingKey.nullIfEmpty(),
        aciIdentityPublicKey = auth.aciIdentityPublicKey.nullIfEmpty(),
        aciIdentityPrivateKey = auth.aciIdentityPrivateKey.nullIfEmpty(),
        aciIdentityOldPublicKey = auth.aciIdentityOldPublicKey.nullIfEmpty(),
        aciIdentityOldPrivateKey = auth.aciIdentityOldPrivateKey.nullIfEmpty(),
        aciIdentityKeyGenTime = auth.aciIdentityKeyGenTime,
        proxyShareLink = auth.proxyShareLink.nullIfEmpty(),
        proxyEnabled = auth.proxyEnabled,
        proxyProtectCallIp = auth.proxyProtectCallIp,
        favKey = auth.favKey.nullIfEmpty(),
        favKeyId = auth.favKeyId.nullIfEmpty(),
        favKeyVersion = auth.favKeyVersion,
    )

    







    fun fromUserData(userData: UserData): UserAuthData = UserAuthData(
        account = userData.account.orEmpty(),
        baseAuth = userData.baseAuth.orEmpty(),
        microToken = userData.microToken.orEmpty(),
        email = userData.email.orEmpty(),
        phoneNumber = userData.phoneNumber.orEmpty(),
        customUid = userData.customUid.orEmpty(),
        contactRequestStatus = userData.contactRequestStatus.orEmpty(),
        passcode = userData.passcode.orEmpty(),
        pattern = userData.pattern.orEmpty(),
        signalingKey = userData.signalingKey.orEmpty(),
        aciIdentityPublicKey = userData.aciIdentityPublicKey.orEmpty(),
        aciIdentityPrivateKey = userData.aciIdentityPrivateKey.orEmpty(),
        aciIdentityOldPublicKey = userData.aciIdentityOldPublicKey.orEmpty(),
        aciIdentityOldPrivateKey = userData.aciIdentityOldPrivateKey.orEmpty(),
        aciIdentityKeyGenTime = userData.aciIdentityKeyGenTime,
        migrationV1Completed = false,
        proxyShareLink = userData.proxyShareLink.orEmpty(),
        proxyEnabled = userData.proxyEnabled,
        proxyProtectCallIp = userData.proxyProtectCallIp,
        favKey = userData.favKey.orEmpty(),
        favKeyId = userData.favKeyId.orEmpty(),
        favKeyVersion = userData.favKeyVersion,
    )

    private fun String.nullIfEmpty(): String? = if (this.isEmpty()) null else this

    





    fun fromLegacyComplete(userData: UserData): UserAuthData =
        fromUserData(userData).copy(migrationV1Completed = true)
}
