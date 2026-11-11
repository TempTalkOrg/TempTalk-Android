package com.difft.android.base.storage










internal object StorageNames {
    
    
    const val SECURE_USER_FILE = "secure_user.pb"
    const val SECURE_CONFIG_FILE = "secure_config.pb"
    const val APP_STATE_FILE = "app_state"  

    
    
    const val SECURE_USER_KEYSET_PREFS_FILE = "tt_storage_secure_user_keyset"
    const val SECURE_USER_KEYSET_PREFS_KEY = "secure_user_keyset"
    const val SECURE_CONFIG_KEYSET_PREFS_FILE = "tt_storage_secure_config_keyset"
    const val SECURE_CONFIG_KEYSET_PREFS_KEY = "secure_config_keyset"

    
    const val KEYSTORE_SECURE_USER_URI = "android-keystore://tt_storage_master_secure_user"
    const val KEYSTORE_SECURE_CONFIG_URI = "android-keystore://tt_storage_master_secure_config"

    
    
    val SECURE_USER_AAD: ByteArray = "tt.storage.secure_user.v1".toByteArray(Charsets.UTF_8)
    val SECURE_CONFIG_AAD: ByteArray = "tt.storage.secure_config.v1".toByteArray(Charsets.UTF_8)
}
