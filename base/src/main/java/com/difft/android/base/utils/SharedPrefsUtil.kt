package com.difft.android.base.utils

import android.content.Context
import androidx.core.content.edit

/**
 * 普通配置可以存储在这里
 *
 * 用户相关配置使用加密存储，请参考:
 * {@link com.difft.android.base.user.UserManager}
 * {@link com.difft.android.base.user.UserData}
 */
object SharedPrefsUtil {


    const val SHARED_PREFS_NAME = "sp_chative_account"

    const val SP_KEY_LANGUAGE = "SP_KEY_LANGUAGE"
    const val SP_NEW_CONFIG = "SP_NEW_CONFIG"
    const val SP_UNREAD_MSG_NUM= "SP_BYC_DOMAINS_TIME"

    private val sharedPreferences by lazy {
        application.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return sharedPreferences.getString(key, defaultValue)
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return sharedPreferences.getLong(key, defaultValue)
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        sharedPreferences.edit {
            putBoolean(key, value)
        }
    }

    fun putInt(key: String, value: Int) {
        sharedPreferences.edit {
            putInt(key, value)
        }
    }

    fun putLong(key: String, value: Long) {
        sharedPreferences.edit {
            putLong(key, value)
        }
    }

    fun putString(key: String, value: String?) {
        sharedPreferences.edit {
            putString(key, value)
        }
    }

    fun remove(key: String) {
        sharedPreferences.edit {
            remove(key)
        }
    }

    fun clear() {
        sharedPreferences.edit(commit = true) {
            clear()
        }
    }
}