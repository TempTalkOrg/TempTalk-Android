package com.difft.android.test.di

import com.difft.android.base.di.module.UserInfoModule
import com.difft.android.base.qualifier.User
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [UserInfoModule::class]
)
object TestUserInfoModule {

    private const val TEST_USER_ID = "test-user-001"

    @Singleton
    @Provides
    fun provideUserManager(): UserManager = object : UserManager {
        private var userData: UserData? = UserData(
            account = TEST_USER_ID,
            password = "test-password",
            baseAuth = "dGVzdC11c2VyLTAwMTp0ZXN0LXBhc3N3b3Jk",
            email = "test@temptalk.org"
        )

        override fun setUserData(userData: UserData, commit: Boolean) {
            this.userData = userData
        }

        override fun getUserData(): UserData? = userData
    }

    @User.Uid
    @Provides
    fun provideUserId(): String = TEST_USER_ID
}
