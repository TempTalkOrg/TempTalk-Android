package com.difft.android.base.storage.schema

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.lumberjack.L
import java.io.File

/**
 * One-shot migration from the legacy `secure_global_config.xml`
 * [EncryptedSharedPreferences] file into the new `secure_config.pb`
 * DataStore (issue #725, Task 4).
 *
 * **Why a custom [DataMigration] instead of `SharedPreferencesMigration`**:
 * we need to project an explicit pair of legacy SP keys
 * (`"config"` + `"call_service_url_state_v3"`) into an opaque-string schema
 * and apply a false-positive guard against silent MasterKey reconstruction
 * failures. The factory variants don't expose the [SharedPreferences]
 * instance directly inside the migrate lambda, which we need to inspect
 * `.contains(key)` for the guard.
 *
 * **MasterKey configuration** must exactly mirror the original writers:
 *   `MasterKey.Builder(context).setKeyScheme(AES256_GCM).build()`
 *   (default alias `_androidx_security_master_key`)
 * as used by both `GlobalConfigsManager.configPrefs` and
 * `CallServiceUrlManager.prefs` — mismatching causes silent decrypt
 * failures (null values for known keys).
 *
 * **False-positive guard** (two distinct paths):
 *   A. Legacy XML file does NOT exist on disk → fresh install → stamp marker
 *      and skip projection. There is no data to migrate.
 *   B. Legacy XML file DOES exist on disk but BOTH known keys are absent
 *      after [EncryptedSharedPreferences.create] succeeds → likely silent
 *      MasterKey reconstruction failure (a known OEM failure mode where
 *      every read returns null). DO NOT stamp the marker — return
 *      [currentData] untouched so DataStore retries `migrate()` on the
 *      next cold start. Permanently stamping here would lose any legacy
 *      config that exists on disk but is temporarily unreadable.
 */
internal class SecureConfigMigration(
    private val context: Context,
) : DataMigration<GlobalConfigData> {

    override suspend fun shouldMigrate(currentData: GlobalConfigData): Boolean =
        !currentData.migrationV1Completed

    override suspend fun migrate(currentData: GlobalConfigData): GlobalConfigData {
        if (!legacyFileExists()) {
            L.i { "[Storage][secure_config][Migration] no legacy file — fresh install, stamp marker" }
            return currentData.copy(migrationV1Completed = true)
        }

        val prefs = try {
            openLegacyEncryptedPrefs()
        } catch (e: Throwable) {
            // MasterKey reconstruction / EncryptedSP.create failure. Do NOT throw —
            // propagating would invalidate DataStore<GlobalConfigData>.data for the
            // entire current session, breaking GlobalConfigsManager.configFlow and
            // CallServiceUrlManager.callServiceUrlStateV3Flow until next launch.
            //
            // Return currentData (marker stays false) so:
            //  - current session: reads succeed and degrade to empty config (callers
            //    like GlobalConfigsManager already fall back to network for empty config)
            //  - next cold start: migrate() retries (marker still false)
            L.w {
                "[Storage][secure_config][Migration] open legacy prefs failed; " +
                    "current session degrades to empty config, will retry next cold start: " +
                    "${e.javaClass.simpleName}"
            }
            return currentData
        }

        val hasLegacyConfig = prefs.contains(LEGACY_KEY_CONFIG)
        val hasLegacyCallState = prefs.contains(LEGACY_KEY_CALL_STATE)

        if (!hasLegacyConfig && !hasLegacyCallState) {
            // Case B: legacy file exists but neither key is visible. Likely a silent
            // MasterKey reconstruction failure. DO NOT stamp — let migration retry.
            L.w {
                "[Storage][secure_config][Migration] legacy file exists but keys unreadable " +
                    "(possible MasterKey reconstruction failure) — leaving marker=false for retry"
            }
            return currentData
        }

        val legacyConfig = prefs.getString(LEGACY_KEY_CONFIG, null).orEmpty()
        val legacyCallState = prefs.getString(LEGACY_KEY_CALL_STATE, null).orEmpty()
        L.i { "[Storage][secure_config][Migration] projecting legacy values configPresent=$hasLegacyConfig callStatePresent=$hasLegacyCallState" }

        // Defensive merge: preserve any DataStore values written between
        // partial migration attempts; otherwise fall back to the legacy value.
        return currentData.copy(
            config = currentData.config.ifEmpty { legacyConfig },
            callServiceUrlStateV3 = currentData.callServiceUrlStateV3.ifEmpty { legacyCallState },
            migrationV1Completed = true,
        )
    }

    /** No-op — legacy SP retained for the rollback retention window (LegacySpCleanup is a follow-up). */
    override suspend fun cleanUp() = Unit

    private fun openLegacyEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            LEGACY_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun legacyFileExists(): Boolean =
        File(context.dataDir, "shared_prefs/$LEGACY_FILE_NAME.xml").exists()

    private companion object {
        const val LEGACY_FILE_NAME = "secure_global_config"
        const val LEGACY_KEY_CONFIG = "config"
        const val LEGACY_KEY_CALL_STATE = "call_service_url_state_v3"
    }
}
