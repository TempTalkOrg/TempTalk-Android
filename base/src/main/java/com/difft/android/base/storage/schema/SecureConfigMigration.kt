package com.difft.android.base.storage.schema

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.lumberjack.L
import java.io.File
































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
            
            
            L.w {
                "[Storage][secure_config][Migration] legacy file exists but keys unreadable " +
                    "(possible MasterKey reconstruction failure) — leaving marker=false for retry"
            }
            return currentData
        }

        val legacyConfig = prefs.getString(LEGACY_KEY_CONFIG, null).orEmpty()
        val legacyCallState = prefs.getString(LEGACY_KEY_CALL_STATE, null).orEmpty()
        L.i { "[Storage][secure_config][Migration] projecting legacy values configPresent=$hasLegacyConfig callStatePresent=$hasLegacyCallState" }

        
        
        return currentData.copy(
            config = currentData.config.ifEmpty { legacyConfig },
            callServiceUrlStateV3 = currentData.callServiceUrlStateV3.ifEmpty { legacyCallState },
            migrationV1Completed = true,
        )
    }

    
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
