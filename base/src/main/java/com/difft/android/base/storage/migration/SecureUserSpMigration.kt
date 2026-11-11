package com.difft.android.base.storage.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.storage.schema.UserAuthDataMapper
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.globalServices
import java.io.File












internal class SecureUserSpMigration(
    private val context: Context,
    






    private val legacyPrefsProvider: () -> SharedPreferences? = { defaultOpenLegacyPrefs(context) },
) : DataMigration<UserAuthData> {

    override suspend fun shouldMigrate(currentData: UserAuthData): Boolean =
        !currentData.migrationV1Completed

    override suspend fun migrate(currentData: UserAuthData): UserAuthData {
        return try {
            
            val prefs = legacyPrefsProvider()
                ?: return currentData.copy(migrationV1Completed = true)

            
            
            val blob = readLegacyBlob(prefs)
            val base = blob?.let { UserAuthDataMapper.fromUserData(it) } ?: currentData

            
            
            
            
            
            
            
            val migratedBaseAuth = base.baseAuth.ifEmpty { secureGet(prefs, KEY_BASIC_AUTH) }
            val migratedMicroToken = base.microToken.ifEmpty { secureGet(prefs, KEY_MICRO_TOKEN) }
            val migratedSignalingKey = base.signalingKey.ifEmpty { secureGet(prefs, KEY_SIGNALING_KEY) }

            val usedLegacyKeyFallback =
                (base.baseAuth.isEmpty() && migratedBaseAuth.isNotEmpty()) ||
                    (base.microToken.isEmpty() && migratedMicroToken.isNotEmpty()) ||
                    (base.signalingKey.isEmpty() && migratedSignalingKey.isNotEmpty())

            val projected = base.copy(
                baseAuth = migratedBaseAuth,
                microToken = migratedMicroToken,
                signalingKey = migratedSignalingKey,
                migrationV1Completed = true,
            )

            L.i {
                "[Storage][secure_user][Migration] complete " +
                    "hasAuth=${projected.baseAuth.isNotEmpty()} " +
                    "hasLegacyBlob=${blob != null} " +
                    "usedLegacyKeyFallback=$usedLegacyKeyFallback"
            }
            projected
        } catch (e: Exception) {
            
            L.e {
                "[Storage][secure_user][Migration] failed; stamping marker on currentData: " +
                    e.stackTraceToString()
            }
            currentData.copy(migrationV1Completed = true)
        }
    }

    
    override suspend fun cleanUp() = Unit

    




    private fun readLegacyBlob(prefs: SharedPreferences): UserData? {
        return try {
            val json = prefs.getString(LEGACY_KEY_USERDATA, null) ?: return null
            globalServices.gson.fromJson(json, UserData::class.java)
        } catch (e: Throwable) {
            L.w {
                "[Storage][secure_user][Migration] legacy blob read failed: " +
                    "${e.javaClass.simpleName} msg=${e.message}"
            }
            null
        }
    }

    




    private fun secureGet(prefs: SharedPreferences, key: String): String {
        return try {
            prefs.getString(key, null).orEmpty()
        } catch (e: Throwable) {
            L.w {
                "[Storage][secure_user][Migration] standalone key read failed key=$key: " +
                    "${e.javaClass.simpleName} msg=${e.message}"
            }
            ""
        }
    }

    companion object {
        private const val LEGACY_SP_FILE = "secure_prefs"

        




        private const val LEGACY_KEY_USERDATA =
            "com.difft.chative.base.user.SimpleUserManager\$Companion.SHARED_PREFERENCES_KEY_USERDATA"

        
        
        private const val KEY_BASIC_AUTH = "basic_auth"
        private const val KEY_MICRO_TOKEN = "micro_token"
        private const val KEY_SIGNALING_KEY = "signaling_key"

        









        private fun defaultOpenLegacyPrefs(context: Context): SharedPreferences? {
            val legacySpFile = File(context.dataDir, "shared_prefs/$LEGACY_SP_FILE.xml")
            if (!legacySpFile.exists()) return null

            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    LEGACY_SP_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Throwable) {
                L.w {
                    "[Storage][secure_user][Migration] legacy SP open failed: " +
                        "${e.javaClass.simpleName} msg=${e.message}"
                }
                null
            }
        }
    }
}
