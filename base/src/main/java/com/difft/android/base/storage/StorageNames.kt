package com.difft.android.base.storage

/**
 * Single source of truth for all storage file names, Tink keyset locations,
 * Android Keystore aliases, and AAD byte sequences used by the storage layer.
 *
 * See `docs/storage` (issue #725) for the full design rationale:
 * - Per-namespace keysets give lifecycle isolation between `secure_user` and
 *   `secure_config` (logout rotates only the auth keyset).
 * - AAD binds ciphertext to a namespace and prevents cross-namespace replay.
 */
internal object StorageNames {
    // DataStore backing files (resolved relative to context.dataStoreFile /
    // preferencesDataStoreFile under filesDir/datastore/).
    const val SECURE_USER_FILE = "secure_user.pb"
    const val SECURE_CONFIG_FILE = "secure_config.pb"
    const val APP_STATE_FILE = "app_state"  // PreferenceDataStoreFactory appends .preferences_pb

    // Tink keyset SharedPreferences locations — one SP file per encrypted namespace.
    // These hold the encrypted keyset bytes wrapped by the Android Keystore master key.
    const val SECURE_USER_KEYSET_PREFS_FILE = "tt_storage_secure_user_keyset"
    const val SECURE_USER_KEYSET_PREFS_KEY = "secure_user_keyset"
    const val SECURE_CONFIG_KEYSET_PREFS_FILE = "tt_storage_secure_config_keyset"
    const val SECURE_CONFIG_KEYSET_PREFS_KEY = "secure_config_keyset"

    // Android Keystore master-key aliases (URIs as required by Tink AndroidKeysetManager).
    const val KEYSTORE_SECURE_USER_URI = "android-keystore://tt_storage_master_secure_user"
    const val KEYSTORE_SECURE_CONFIG_URI = "android-keystore://tt_storage_master_secure_config"

    // AAD constants — bind ciphertext to namespace.
    // Versioned ("v1") so a future scheme bump can break compatibility deliberately.
    val SECURE_USER_AAD: ByteArray = "tt.storage.secure_user.v1".toByteArray(Charsets.UTF_8)
    val SECURE_CONFIG_AAD: ByteArray = "tt.storage.secure_config.v1".toByteArray(Charsets.UTF_8)
}
