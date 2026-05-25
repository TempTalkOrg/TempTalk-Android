package com.difft.android.base.storage.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Encrypted proto `DataStore<UserAuthData>` payload — 15 auth/identity fields carved
 * out of the legacy 43-field `UserData` blob (`SHARED_PREFERENCES_KEY_USERDATA` in
 * `secure_prefs` EncryptedSP), plus a defense-in-depth migration marker.
 *
 * **All String fields are non-nullable** with empty-string default — `kotlinx-serialization-protobuf`
 * does NOT support nullable properties (proto wire format has no explicit null). Conversion
 * between `String?` (legacy `UserData` API) and `String` happens at the mapper boundary
 * ([UserAuthDataMapper]): `null` ↔ `""`. Downstream callers continue to see nullable
 * via the legacy [com.difft.android.base.user.UserData] type.
 *
 * **MUST encrypt** (9): `baseAuth`, `microToken`, `signalingKey`, `passcode`, `pattern`,
 *  `aciIdentityPrivateKey`, `aciIdentityOldPrivateKey`, `email`, `phoneNumber`.
 *
 * **Could encrypt** (6, kept together for lifecycle isolation): `account`, `customUid`,
 *  `aciIdentityPublicKey`, `aciIdentityOldPublicKey`, `aciIdentityKeyGenTime`,
 *  `contactRequestStatus`.
 *
 * **Not here**: `searchByCustomUid` (Int feature flag) lives in `app_state` — it's a
 * UX toggle, not identity material. The legacy `UserData.password` field was dropped
 * in Task 1 (zero readers/writers).
 *
 * **Migration marker**: [migrationV1Completed] is a defense-in-depth flag on top of
 * DataStore's internal migration marker. Set to `true` ONLY inside the migration's
 * `migrate()` lambda (atomic with the field projection write). NEVER a content-based
 * heuristic — a user with valid `baseAuth`/`microToken` but no `account` would
 * otherwise be mis-classified as "not migrated yet" and have their auth silently
 * overwritten on every cold start.
 *
 * See design report §2.2 for the full carve-out rationale.
 *
 * **Tag stability contract (`@ProtoNumber`)**: explicit field numbers below match the
 * implicit declaration-order tags that PR #789 shipped (1..16). Any future schema change
 * MUST preserve these numbers — wire format on every deployed device depends on them.
 *  - **Add a field**: append at the bottom with the next unused tag (17+).
 *  - **Remove a field**: delete the line; **never reuse** the freed tag number.
 *  - **Rename a field**: free — tag is the contract, not the Kotlin name.
 *  - **Reorder fields**: free — `@ProtoNumber` decouples wire format from declaration order.
 * Verified by [com.difft.android.base.storage.schema.SchemaWireFormatTest].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UserAuthData(
    @ProtoNumber(1) val account: String = "",
    @ProtoNumber(2) val baseAuth: String = "",
    @ProtoNumber(3) val microToken: String = "",
    @ProtoNumber(4) val email: String = "",
    @ProtoNumber(5) val phoneNumber: String = "",
    @ProtoNumber(6) val customUid: String = "",
    @ProtoNumber(7) val contactRequestStatus: String = "",
    @ProtoNumber(8) val passcode: String = "",
    @ProtoNumber(9) val pattern: String = "",
    @ProtoNumber(10) val signalingKey: String = "",
    @ProtoNumber(11) val aciIdentityPublicKey: String = "",
    @ProtoNumber(12) val aciIdentityPrivateKey: String = "",
    @ProtoNumber(13) val aciIdentityOldPublicKey: String = "",
    @ProtoNumber(14) val aciIdentityOldPrivateKey: String = "",
    @ProtoNumber(15) val aciIdentityKeyGenTime: Long = 0L,
    /**
     * Defense-in-depth migration marker (on top of DataStore's internal marker).
     * Same pattern as `GlobalConfigData.migrationV1Completed`. Stamped as the last
     * action of [com.difft.android.base.storage.migration.SecureUserSpMigration.migrate]
     * — atomically with the 15 field projections in one `updateData` write.
     */
    @ProtoNumber(16) val migrationV1Completed: Boolean = false,
) {
    companion object {
        val EMPTY = UserAuthData()
    }
}
