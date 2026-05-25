package com.difft.android.base.storage.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Encrypted DataStore payload for `secure_config.pb` (issue #725, Task 4).
 *
 * Holds the two opaque JSON blobs previously stored under
 * `secure_global_config.xml` via [androidx.security.crypto.EncryptedSharedPreferences]:
 *
 *  - [config] — `GlobalConfigsManager`'s `NewGlobalConfig` Gson blob (legacy key `"config"`).
 *  - [callServiceUrlStateV3] — `CallServiceUrlManager`'s `CallServiceUrlDiskState`
 *    kotlinx-serialization blob (legacy key `"call_service_url_state_v3"`).
 *
 * Both are intentionally kept as **opaque [String]** rather than nested
 * `@Serializable` types, because:
 *  - `NewGlobalConfig` is a 100+-field Gson type in `:base/user/` and
 *    re-annotating its full tree would bloat the migration scope.
 *  - `CallServiceUrlDiskState` is `internal` to the `:call` module; lifting
 *    it into `:base` would require visibility relaxation across module
 *    boundaries.
 *  - Opaque migration is byte-identical to the legacy reader, so there is
 *    no schema-drift risk between the old [EncryptedSharedPreferences] path
 *    and the new DataStore path.
 *
 * Empty-string sentinel (rather than `null`) is used for the payload fields
 * so the data class has a stable proto default and downstream `.first()`
 * reads do not need null guards everywhere — a fresh install yields
 * `config = ""` and `callServiceUrlStateV3 = ""`, which callers treat as
 * "no cached value, fall back to defaults/network".
 *
 * The [migrationV1Completed] marker is a defense-in-depth flag layered on
 * top of DataStore's internal migration tracker; it is set to `true` by
 * `SecureConfigMigration` after the legacy `EncryptedSharedPreferences`
 * read succeeds, and prevents repeated migration attempts on every cold
 * start.
 *
 * **Tag stability contract (`@ProtoNumber`)**: explicit field numbers below match the
 * implicit declaration-order tags that PR #789 shipped (1..3). Wire format on every
 * deployed `secure_config.pb` depends on these numbers — see [UserAuthData] for the
 * same rule (append at the bottom with next unused tag; never reuse a freed tag).
 * Verified by [com.difft.android.base.storage.schema.SchemaWireFormatTest].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GlobalConfigData(
    /** `GlobalConfigsManager`'s `NewGlobalConfig` Gson blob (legacy key `"config"`). */
    @ProtoNumber(1) val config: String = "",
    /** `CallServiceUrlManager`'s `CallServiceUrlDiskState` JSON blob (legacy key `"call_service_url_state_v3"`). */
    @ProtoNumber(2) val callServiceUrlStateV3: String = "",
    /** Defense-in-depth migration marker — set by `SecureConfigMigration` after a successful legacy SP read. */
    @ProtoNumber(3) val migrationV1Completed: Boolean = false,
) {
    companion object {
        val EMPTY = GlobalConfigData()
    }
}
