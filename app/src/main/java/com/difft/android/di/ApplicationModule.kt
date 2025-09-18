package com.difft.android.di

import com.difft.android.base.user.LogoutManager
import com.difft.android.setting.LogoutManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
interface ApplicationModule {
    @Binds
    @Singleton
    fun bindLogoutManager(logoutManagerImpl: LogoutManagerImpl): LogoutManager
}