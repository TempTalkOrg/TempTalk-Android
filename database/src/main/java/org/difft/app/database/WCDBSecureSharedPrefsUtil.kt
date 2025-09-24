package org.difft.app.database

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class WCDBSecureSharedPrefsUtil(context: Context) {

    private val masterKeyAlias: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "wcdb_secure_prefs",
            masterKeyAlias,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun putString(key: String, value: String?) {
        try {
            sharedPreferences.edit {
                putString(key, value)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    fun getString(key: String, defaultValue: String? = null): String? {
        try {
            return if (sharedPreferences.contains(key)) {
                sharedPreferences.getString(key, defaultValue)
            } else {
                defaultValue
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return defaultValue
        }
    }

    fun clear() {
        sharedPreferences.edit(commit = true) {
            clear()
        }
    }

}