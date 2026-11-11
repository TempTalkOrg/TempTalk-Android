package com.difft.android.app.startup

import com.difft.android.base.user.UserData

























fun needsIdentityKeyRelogin(userData: UserData?): Boolean {
    if (userData == null) return false
    if (userData.baseAuth.isNullOrEmpty()) return false

    return userData.aciIdentityPublicKey.isNullOrEmpty()
        || userData.aciIdentityPrivateKey.isNullOrEmpty()
}
