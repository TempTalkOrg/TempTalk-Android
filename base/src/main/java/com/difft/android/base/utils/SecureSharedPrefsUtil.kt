package com.difft.android.base.utils

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent


object SecureSharedPrefsUtil {


    private const val KEY_BASIC_AUTH = "basic_auth"
    private const val KEY_MICRO_TOKEN = "micro_token"
    private const val KEY_SIGNALING_KEY = "signaling_key"

    private val masterKeyAlias: MasterKey by lazy {
        MasterKey.Builder(application)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    private val userManager: UserManager by lazy { globalServices.userManager }

    private val sharedPreferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            application,
            "secure_prefs",
            masterKeyAlias,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun putString(key: String, value: String?, commit: Boolean = false) {
        try {
            sharedPreferences.edit(commit = commit) {
                putString(key, value)
            }
        } catch (e: Exception) {
            L.w { "[SecureSharedPrefsUtil] error: ${e.stackTraceToString()}" }
            L.w { "[SecureSharedPrefsUtil] putString fail:$key===${e.stackTraceToString()}" }
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
            L.w { "[SecureSharedPrefsUtil] error: ${e.stackTraceToString()}" }
            L.w { "[SecureSharedPrefsUtil] getString fail:$key===${e.stackTraceToString()}" }
            return defaultValue
        }
    }

//    fun saveBasicAuth(auth: String?) {
//        saveString(KEY_BASIC_AUTH, auth)
//    }

    fun getBasicAuth(): String {
        val basicAuth = userManager.getUserData()?.baseAuth ?: getString(KEY_BASIC_AUTH) ?: ""
//        L.i { "[userdata]======basicAuth========${basicAuth.length}" }
        return basicAuth
    }

//    fun saveToken(token: String?) {
//        saveString(KEY_MICRO_TOKEN, token)
//    }


    fun getToken(): String {
        val microToken = userManager.getUserData()?.microToken ?: getString(KEY_MICRO_TOKEN) ?: ""
//        L.i { "[userdata]======microToken========${microToken.length}" }
        return microToken
    }

    fun getSignalingKey(): String {
        val signalingKey = userManager.getUserData()?.signalingKey ?: getString(KEY_SIGNALING_KEY) ?: ""
        return signalingKey
    }

    fun clear() {
        sharedPreferences.edit(commit = true) {
            clear()
        }
    }
}


