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

/**
 * One-shot migration from the legacy encrypted SP file (`secure_prefs`) into the typed
 * `DataStore<UserAuthData>` (`secure_user.pb`). Runs exactly once, gated on
 * `!migrationV1Completed`.
 *
 * Primary source is the single Gson-serialized [UserData] blob (issue #725). For
 * "1.1.8-era" users whose credentials live ONLY in the legacy standalone keys
 * (`basic_auth`/`micro_token`/`signaling_key`) with an empty `baseAuth` in the blob, we
 * fall back to those standalone keys — fill-empty-only, so a normal logged-in user's blob
 * values are never overwritten.
 */
internal class SecureUserSpMigration(
    private val context: Context,
    /**
     * Test seam: supplies the legacy `secure_prefs` [SharedPreferences] handle, or `null`
     * when the file is absent (fresh install). Production default opens the real
     * [EncryptedSharedPreferences]. Tests inject a plain in-memory `SharedPreferences`
     * because `EncryptedSharedPreferences.create` requires the AndroidKeyStore provider,
     * which Robolectric does not supply (`NoSuchAlgorithmException`).
     */
    private val legacyPrefsProvider: () -> SharedPreferences? = { defaultOpenLegacyPrefs(context) },
) : DataMigration<UserAuthData> {

    override suspend fun shouldMigrate(currentData: UserAuthData): Boolean =
        !currentData.migrationV1Completed

    override suspend fun migrate(currentData: UserAuthData): UserAuthData {
        return try {
            // No legacy file at all → fresh install. Skip fallback, just stamp the marker.
            val prefs = legacyPrefsProvider()
                ?: return currentData.copy(migrationV1Completed = true)

            // Project the legacy blob. The migration runs only once (pre-v1), so currentData
            // is always empty here and the blob is the source of truth.
            val blob = readLegacyBlob(prefs)
            val base = blob?.let { UserAuthDataMapper.fromUserData(it) } ?: currentData

            // Standalone-key fallback (fill-empty-only) — always runs even when the blob is
            // absent (standalone-only legacy users never had a blob). A non-empty blob value
            // is NEVER overwritten, so normal logged-in users are not clobbered.
            // No standalone fallback for `account`: it has no independent key and is read only
            // from the blob. Genuinely logged-in legacy users always have account in the blob
            // (the old verifyLocalToken required a non-empty account, read from the blob), so
            // only baseAuth/microToken/signalingKey need standalone-key fallback.
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
            // Stamp the marker on whatever we have to prevent infinite retry; user re-logs in on next 401.
            L.e {
                "[Storage][secure_user][Migration] failed; stamping marker on currentData: " +
                    e.stackTraceToString()
            }
            currentData.copy(migrationV1Completed = true)
        }
    }

    /** No-op — legacy SP stays for the rollback retention window; marker prevents re-run. */
    override suspend fun cleanUp() = Unit

    /**
     * Reads the legacy Gson-serialized [UserData] blob from the already-open [prefs].
     * Returns `null` if the key is absent or the JSON is malformed — both funnel to the
     * standalone-key fallback (the blob is optional for standalone-only legacy users).
     */
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

    /**
     * Reads a single standalone string key from the already-open [prefs], returning "" on
     * absence or any read failure. Mirrors the legacy `SecureSharedPrefsUtil.getString`
     * read-fallback. Never logs the value.
     */
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

        /**
         * Verbatim copy of `SimpleUserManager.SHARED_PREFERENCES_KEY_USERDATA`. Duplicated
         * (NOT imported) to keep the legacy key as a frozen migration constant — even if
         * `SimpleUserManager` is later renamed/refactored, this string must not change.
         */
        private const val LEGACY_KEY_USERDATA =
            "com.difft.chative.base.user.SimpleUserManager\$Companion.SHARED_PREFERENCES_KEY_USERDATA"

        // Standalone legacy auth keys — verbatim from the removed `SecureSharedPrefsUtil`.
        // Frozen migration constants; must not change.
        private const val KEY_BASIC_AUTH = "basic_auth"
        private const val KEY_MICRO_TOKEN = "micro_token"
        private const val KEY_SIGNALING_KEY = "signaling_key"

        /**
         * Production opener for the legacy `secure_prefs` [EncryptedSharedPreferences] handle,
         * reused for BOTH the blob read and the 3 standalone-key reads. Returns `null` if:
         *  - the file doesn't exist (fresh install — `EncryptedSharedPreferences.create` would
         *    otherwise write Tink bootstrap entries on first access),
         *  - the MasterKey can't be reconstructed (Keystore reset),
         *  - the EncryptedSharedPreferences fails to open.
         *
         * A `null` here funnels the caller to stamping the marker with no fallback.
         */
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
