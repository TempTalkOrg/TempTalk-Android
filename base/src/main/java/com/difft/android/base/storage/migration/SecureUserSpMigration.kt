package com.difft.android.base.storage.migration

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.storage.schema.UserAuthDataMapper
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.globalServices
import java.io.File

/**
 * One-shot migration from the legacy encrypted SP file (`secure_prefs`) into the
 * typed `DataStore<UserAuthData>` (`secure_user.pb`).
 *
 * The legacy storage holds a single Gson-serialized `UserData` blob under the
 * `SHARED_PREFERENCES_KEY_USERDATA` key (see `SimpleUserManager`). We read that
 * blob, project the 15 auth fields via [UserAuthDataMapper.fromLegacyComplete],
 * and return the resulting [UserAuthData] with `migrationV1Completed = true`.
 * DataStore commits the marker and the 15 field projections atomically in one
 * disk write.
 *
 * **Idempotency**: two layers — DataStore's internal "migration completed" marker
 * (primary, prevents re-run), plus the explicit [UserAuthData.migrationV1Completed]
 * field on the payload itself (defense in depth).
 *
 * **Cleanup**: legacy `secure_prefs` entry stays readable for the 3-release
 * retention window as v(N+1) → v(N) rollback safety net. Deletion lives in a
 * future `LegacySpCleanup` step (explicitly out-of-scope for issue #725).
 *
 * **Error recovery**: any failure reading the legacy SP (Keystore reset,
 * EncryptedSharedPreferences corruption, OEM-specific MasterKey invalidation)
 * still stamps `migrationV1Completed = true` on EMPTY data — otherwise the
 * migration would re-fire on every cold start in an infinite retry loop.
 * The user is routed through normal "no auth" recovery (re-login).
 */
internal class SecureUserSpMigration(
    private val context: Context,
) : DataMigration<UserAuthData> {

    override suspend fun shouldMigrate(currentData: UserAuthData): Boolean =
        !currentData.migrationV1Completed

    override suspend fun migrate(currentData: UserAuthData): UserAuthData {
        return try {
            val legacyBlob = readLegacyBlob()
            val projected = if (legacyBlob != null) {
                UserAuthDataMapper.fromLegacyComplete(legacyBlob)
            } else {
                // Fresh install or unreadable legacy file — stamp marker to avoid infinite retry.
                currentData.copy(migrationV1Completed = true)
            }
            L.i {
                "[Storage][secure_user][Migration] complete " +
                    "hasAuth=${!projected.baseAuth.isNullOrEmpty()} " +
                    "hasLegacyBlob=${legacyBlob != null}"
            }
            projected
        } catch (e: Exception) {
            // Stamp the marker on EMPTY data to prevent infinite retry; user re-logs in on next 401.
            L.e {
                "[Storage][secure_user][Migration] failed; stamping marker on EMPTY: " +
                    e.stackTraceToString()
            }
            currentData.copy(migrationV1Completed = true)
        }
    }

    /** No-op — legacy SP stays for the rollback retention window; marker prevents re-run. */
    override suspend fun cleanUp() = Unit

    /**
     * Reads the legacy Gson-serialized [UserData] blob from `secure_prefs`.
     * Returns `null` if:
     *  - the file doesn't exist (fresh install),
     *  - the key is absent,
     *  - the MasterKey can't be reconstructed (Keystore reset),
     *  - the EncryptedSharedPreferences fails to decrypt the value,
     *  - the JSON is malformed.
     *
     * All failure modes funnel through `null`, and the caller stamps
     * `migrationV1Completed = true` on EMPTY to break the retry loop.
     */
    private fun readLegacyBlob(): UserData? {
        // EncryptedSharedPreferences.create writes Tink bootstrap entries on first access; skip if no legacy file.
        val legacySpFile = File(context.dataDir, "shared_prefs/$LEGACY_SP_FILE.xml")
        if (!legacySpFile.exists()) return null

        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                LEGACY_SP_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val json = prefs.getString(LEGACY_KEY_USERDATA, null) ?: return null
            globalServices.gson.fromJson(json, UserData::class.java)
        } catch (e: Throwable) {
            L.w {
                "[Storage][secure_user][Migration] legacy SP read failed: " +
                    "${e.javaClass.simpleName} msg=${e.message}"
            }
            null
        }
    }

    companion object {
        private const val LEGACY_SP_FILE = "secure_prefs"

        /**
         * Verbatim copy of `SimpleUserManager.SHARED_PREFERENCES_KEY_USERDATA`. Duplicated
         * (NOT imported) to keep the legacy key as a frozen migration constant — even if
         * `SimpleUserManager` is later renamed/refactored, this string must not change.
         */
        private const val LEGACY_KEY_USERDATA =
            "com.difft.chative.base.user.SimpleUserManager\$Companion.SHARED_PREFERENCES_KEY_USERDATA"
    }
}
