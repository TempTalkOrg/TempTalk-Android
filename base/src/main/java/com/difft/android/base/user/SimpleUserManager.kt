package com.difft.android.base.user

import android.text.TextUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SimpleUserManager : UserManager {
    companion object {
        //        private val SHARED_PREFERENCES_KEY_USERDATA = this::class.java.name + ".SHARED_PREFERENCES_KEY_USERDATA"
        private const val SHARED_PREFERENCES_KEY_USERDATA = "com.difft.chative.base.user.SimpleUserManager\$Companion" + ".SHARED_PREFERENCES_KEY_USERDATA"
    }

    private val gson by lazy { globalServices.gson }

    private var inMemoryUserData: UserData? = null

    override fun setUserData(userData: UserData, commit: Boolean) {
        inMemoryUserData = userData

        val jsonStr = gson.toJson(userData)
        SecureSharedPrefsUtil.putString(SHARED_PREFERENCES_KEY_USERDATA, jsonStr, commit)
    }

    override fun getUserData(): UserData? {
        if (inMemoryUserData != null) return inMemoryUserData

        val secureUserData = SecureSharedPrefsUtil.getString(SHARED_PREFERENCES_KEY_USERDATA)
            ?.let { gson.fromJson(it, UserData::class.java) }
        val userData = if (null != secureUserData) {
            inMemoryUserData = secureUserData
//            L.i { "[userdata]=======getSecureUserData=======" }
            secureUserData
        } else {
            val userData = SharedPrefsUtil.getString(SHARED_PREFERENCES_KEY_USERDATA)
                ?.let { gson.fromJson(it, UserData::class.java) }
            //兼容老版更新，将老版本数据转存到SecureSharedPrefsUtil中
            if (!TextUtils.isEmpty(userData?.toString())) {
                // 在迁移数据时使用异步写入，因为这不是关键操作
                SecureSharedPrefsUtil.putString(SHARED_PREFERENCES_KEY_USERDATA, gson.toJson(userData))
                SharedPrefsUtil.remove(SHARED_PREFERENCES_KEY_USERDATA)
            }
            inMemoryUserData = userData
//            L.i { "[userdata]=======getUserData=======" }
            userData
        }
        return userData
    }
}