package com.difft.android.chat.group

import com.difft.android.base.migration.pause.MigrationPauseScope
import com.difft.android.base.user.UserManager

/** The account and opaque login hash that authorized a group operation; never a credential. */
data class GroupWriteSession(val accountId: String, val creatorLogin: String) {
    fun isCurrent(userManager: UserManager): Boolean {
        val user = userManager.getUserData() ?: return false
        val credential = user.baseAuth?.takeIf { it.isNotBlank() } ?: return false
        return user.account == accountId && MigrationPauseScope.credentialHash(credential) == creatorLogin
    }
}

internal fun requireGroupWriteSession(allowed: (() -> Boolean)?) {
    check(allowed?.invoke() != false) { "group_session_changed" }
}
