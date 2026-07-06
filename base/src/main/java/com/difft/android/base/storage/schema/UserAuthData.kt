package com.difft.android.base.storage.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Encrypted proto `DataStore<UserAuthData>` payload — 17 auth/identity + self-hosted
 * proxy fields, plus a defense-in-depth migration marker. Carved out of the legacy
 * 43-field `UserData` blob (`SHARED_PREFERENCES_KEY_USERDATA` in `secure_prefs`
 * EncryptedSP); the self-hosted proxy share-link + on/off flag were added later
 * (tags 17, 18) since the share-link embeds a TURN `static-auth-secret` and is
 * lifecycle-bound to the user.
 *
 * **All String fields are non-nullable** with empty-string default — `kotlinx-serialization-protobuf`
 * does NOT support nullable properties (proto wire format has no explicit null). Conversion
 * between `String?` (legacy `UserData` API) and `String` happens at the mapper boundary
 * ([UserAuthDataMapper]): `null` ↔ `""`. Downstream callers continue to see nullable
 * via the legacy [com.difft.android.base.user.UserData] type.
 *
 * **MUST encrypt** (10): `baseAuth`, `microToken`, `signalingKey`, `passcode`, `pattern`,
 *  `aciIdentityPrivateKey`, `aciIdentityOldPrivateKey`, `email`, `phoneNumber`,
 *  `proxyShareLink` (embeds the TURN `static-auth-secret` when present).
 *
 * **Could encrypt** (7, kept together for lifecycle isolation): `account`, `customUid`,
 *  `aciIdentityPublicKey`, `aciIdentityOldPublicKey`, `aciIdentityKeyGenTime`,
 *  `contactRequestStatus`, `proxyEnabled` (not a secret per se, but lifecycle-bound to
 *  the user — co-located with `proxyShareLink` for free encryption).
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
 * implicit declaration-order tags that PR #789 shipped (1..16); tags 17..19 were
 * appended later for self-hosted proxy state, tags 20..22 for the favorites favKey.
 * Tags 1..22 are stable now — any future schema change MUST preserve them; wire format
 * on every deployed device depends on them.
 *  - **Add a field**: append at the bottom with the next unused tag (23+).
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
    /**
     * Self-hosted proxy share-link (`ytp://config?d=...`). Embeds a coturn
     * `static-auth-secret` when TURN media-relay is configured — therefore lives
     * in the encrypted half. Empty string = absent (mapper boundary converts to
     * `null` in [com.difft.android.base.user.UserData.proxyShareLink]).
     */
    @ProtoNumber(17) val proxyShareLink: String = "",
    /**
     * User's on/off intent for the self-hosted proxy. Orthogonal to whether
     * [proxyShareLink] parses successfully — the settings UI can hold an
     * invalid-but-displayed link while routing stays off.
     */
    @ProtoNumber(18) val proxyEnabled: Boolean = false,
    /**
     * Whether call/meeting network traffic is routed through the proxy. Gated by
     * [proxyEnabled]: meaningless (and treated as off) while the proxy is off. Kept
     * in the encrypted half alongside the other proxy state for lifecycle isolation.
     */
    @ProtoNumber(19) val proxyProtectCallIp: Boolean = false,
    /**
     * Favorites (GIF) account-level secret. [favKey] decrypts the server-held favorites blob —
     * account-level secret material, so it lives in the encrypted half (same protection as
     * baseAuth/identity keys). Stored Base64 NO_WRAP of the raw 32-byte AES-256 key. Empty string
     * = absent (mapper boundary converts to `null`). Decoupled from WCDB health so a DB
     * corruption-recovery reset does not lose it (the blob is re-pullable + re-decryptable).
     */
    @ProtoNumber(20) val favKey: String = "",
    /** favKey fingerprint. Non-empty = "a key is stored" (the version gate keys off presence). */
    @ProtoNumber(21) val favKeyId: String = "",
    /** Monotonic key-version gate (server-assigned). Meaningless unless [favKeyId] is non-empty. */
    @ProtoNumber(22) val favKeyVersion: Int = 0,
) {
    companion object {
        val EMPTY = UserAuthData()
    }
}
