package com.difft.android.setting.repo

import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.globalServices
import com.difft.android.setting.mvi.ScreenLockContract
import javax.inject.Inject

class ScreenLockSettingsRepository @Inject constructor(private val users: UserManager) {
    fun accountId(): String = globalServices.myId
    fun read(): ScreenLockContract.State {
        val user = users.getUserData()
        return ScreenLockContract.State(
            pattern = !user?.pattern.isNullOrEmpty(), passcode = !user?.passcode.isNullOrEmpty(),
            showPath = user?.patternShowPath ?: true, timeout = user?.passcodeTimeout,
        )
    }
    fun needsBinding(): Boolean = users.getUserData().let { it?.email.isNullOrEmpty() && it?.phoneNumber.isNullOrEmpty() }
    fun setShowPath(enabled: Boolean) { users.update { patternShowPath = enabled } }
    fun setTimeout(seconds: Int) { users.update { passcodeTimeout = seconds } }
    fun disable(accountId: String, kind: ScreenLockContract.Kind): Boolean {
        if (accountId != accountId()) return false
        var applied = false
        // Memory is updated synchronously; the disk write must not block the result callback's thread.
        users.update {
            if (account != accountId) return@update
            applied = true
            when (kind) {
                ScreenLockContract.Kind.Pattern -> { pattern = null; patternShowPath = true; patternAttempts = 0 }
                ScreenLockContract.Kind.Passcode -> { passcode = null; passcodeAttempts = 0; passcodeAutoMisses = 0 }
            }
        }
        return applied
    }
}
