package com.difft.android.base.storage.schema

import com.difft.android.base.user.UserData

/**
 * Bidirectional mapping between the legacy [UserData] blob and the encrypted
 * [UserAuthData] payload (17 fields: 15 auth/identity + 2 self-hosted-proxy).
 *
 * Used by:
 *  1. [com.difft.android.base.storage.migration.SecureUserSpMigration] — projects
 *     a legacy [UserData] read from `secure_prefs` into a [UserAuthData] with
 *     `migrationV1Completed = true` (via [fromLegacyComplete]).
 *  2. The dual-write path (Task 7 `StorageBoundUserManagerImpl`) — mirrors
 *     DataStore writes back to legacy SP via [toUserData].
 *  3. Warm-up reassembly — composes a full [UserData] from auth + app-state.
 *
 * Single source of truth. Adding/renaming an auth field requires updating
 * [UserAuthData], this mapper, and [com.difft.android.base.storage.user.UserDataFieldRouter]
 * (Task 7) — guarded by a Robolectric round-trip test.
 *
 * **Field count invariant**: 17 fields mapped both directions. The non-auth UX fields
 * are preserved verbatim from the `base` argument in [toUserData]; they never
 * round-trip through this mapper.
 */
object UserAuthDataMapper {

    /**
     * Project [auth] onto [base], keeping the 28 non-auth fields from `base`.
     * Used during warm-up to reassemble a full `UserData` view from the split storages.
     *
     * Boundary conversion: empty string in [UserAuthData] → `null` in [UserData].
     * This preserves the legacy `String?` contract for downstream callers.
     */
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
    )

    /**
     * Extract the 17 auth + proxy fields from [userData]. The
     * [UserAuthData.migrationV1Completed] marker is left at its default `false` —
     * only set during the migration path via [fromLegacyComplete].
     *
     * Boundary conversion: `null` in [UserData] → empty string in [UserAuthData].
     * Required because `kotlinx-serialization-protobuf` does NOT support nullable properties.
     */
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
    )

    private fun String.nullIfEmpty(): String? = if (this.isEmpty()) null else this

    /**
     * Same as [fromUserData] but with `migrationV1Completed = true`. Called ONLY
     * from inside [com.difft.android.base.storage.migration.SecureUserSpMigration.migrate]
     * so the marker write is atomic with the 15 field projections (single
     * DataStore `updateData` write).
     */
    fun fromLegacyComplete(userData: UserData): UserAuthData =
        fromUserData(userData).copy(migrationV1Completed = true)
}
