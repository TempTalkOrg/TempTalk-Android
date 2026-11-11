package com.difft.android.migration.v2.handoff

import android.app.KeyguardManager
import android.content.Context
import com.difft.android.base.user.UserData










object HandoffAuthPolicy {

    enum class Method {
        
        APP_LOCK,

        
        DEVICE_CREDENTIAL,

        
        NONE,
    }

    fun required(context: Context, userData: UserData?): Method = when {
        !userData?.passcode.isNullOrEmpty() || !userData?.pattern.isNullOrEmpty() -> Method.APP_LOCK
        isDeviceSecure(context) -> Method.DEVICE_CREDENTIAL
        else -> Method.NONE
    }

    private fun isDeviceSecure(context: Context): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }
            .getOrDefault(false)
}
