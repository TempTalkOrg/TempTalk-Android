package com.difft.android.base.di.module

import com.difft.android.base.qualifier.User
import com.difft.android.base.user.SimpleUserManager
import com.difft.android.base.user.UserManager
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UserInfoModule {
    @Singleton
    @Provides
    fun provideUserManager(): UserManager {
        return SimpleUserManager()
    }

    @User.Uid
    @Provides
    fun provideUserId(userManager: Lazy<UserManager>): String {
        return userManager.get().getUserData()?.account ?: ""
    }
}