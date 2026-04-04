package com.difft.android.test.fakes

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager

/**
 * In-memory UserManager for tests. No SharedPreferences dependency.
 *
 * Pre-populated with a default test user. Set via [setUserData] to null
 * to simulate logged-out state.
 */
class FakeUserManager(
    private var userData: UserData? = DEFAULT_USER_DATA
) : UserManager {

    override fun setUserData(userData: UserData, commit: Boolean) {
        this.userData = userData
    }

    override fun getUserData(): UserData? = userData

    fun clearUserData() {
        userData = null
    }

    companion object {
        const val TEST_USER_ID = "test-user-001"
        const val TEST_PASSWORD = "test-password"
        const val TEST_BASE_AUTH = "dGVzdC11c2VyLTAwMTp0ZXN0LXBhc3N3b3Jk"

        val DEFAULT_USER_DATA = UserData(
            account = TEST_USER_ID,
            password = TEST_PASSWORD,
            baseAuth = TEST_BASE_AUTH,
            email = "test@temptalk.org"
        )
    }
}
