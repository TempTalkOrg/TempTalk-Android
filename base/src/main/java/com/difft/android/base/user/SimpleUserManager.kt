package com.difft.android.base.user

import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.globalServices
import javax.inject.Singleton

@Singleton
class SimpleUserManager : UserManager {
    companion object {
        private const val SHARED_PREFERENCES_KEY_USERDATA = "com.difft.chative.base.user.SimpleUserManager\$Companion" + ".SHARED_PREFERENCES_KEY_USERDATA"
    }

    private val gson by lazy { globalServices.gson }

    @Volatile
    private var inMemoryUserData: UserData? = null

    override fun setUserData(userData: UserData, commit: Boolean) {
        inMemoryUserData = userData

        val jsonStr = gson.toJson(userData)
        SecureSharedPrefsUtil.putString(SHARED_PREFERENCES_KEY_USERDATA, jsonStr, commit)
    }

    override fun getUserData(): UserData? {
        return inMemoryUserData ?: initialUserData.also { inMemoryUserData = it }
    }

    // Lazy load from SecureSharedPrefs or SharedPrefs (thread-safe, only executes once)
    private val initialUserData: UserData? by lazy {
        // Try SecureSharedPrefs first
        val secureUserData = try {
            SecureSharedPrefsUtil.getString(SHARED_PREFERENCES_KEY_USERDATA)
                ?.let { gson.fromJson(it, UserData::class.java) }
        } catch (e: Exception) {
            L.e { "[SimpleUserManager] load from SecureSharedPrefs error: ${e.message}" }
            null
        }

        if (secureUserData != null) {
            L.i { "[SimpleUserManager] Loaded userData from SecureSharedPrefs" }
            return@lazy secureUserData
        }

        // Fallback to legacy SharedPrefs and migrate
        val legacyUserData = try {
            SharedPrefsUtil.getString(SHARED_PREFERENCES_KEY_USERDATA)
                ?.let { gson.fromJson(it, UserData::class.java) }
        } catch (e: Exception) {
            L.e { "[SimpleUserManager] load from SharedPrefs error: ${e.message}" }
            null
        }

        if (legacyUserData != null && !TextUtils.isEmpty(legacyUserData.toString())) {
            L.i { "[SimpleUserManager] Migrating userData from SharedPrefs to SecureSharedPrefs" }
            // Migrate to SecureSharedPrefs
            SecureSharedPrefsUtil.putString(SHARED_PREFERENCES_KEY_USERDATA, gson.toJson(legacyUserData))
            SharedPrefsUtil.remove(SHARED_PREFERENCES_KEY_USERDATA)
        }

        legacyUserData
    }
}