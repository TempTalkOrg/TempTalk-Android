package com.difft.android.base.di.module

import com.difft.android.base.qualifier.User
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.storage.user.StorageBoundUserManagerImpl
import com.difft.android.base.user.UserManager
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton






@Module
@InstallIn(SingletonComponent::class)
object UserInfoModule {

    @User.Uid
    @Provides
    fun provideUserId(userManager: Lazy<UserManager>): String {
        return userManager.get().getUserData()?.account ?: ""
    }
}






@Module
@InstallIn(SingletonComponent::class)
abstract class StorageBindings {

    @Binds
    @Singleton
    abstract fun bindUserManager(impl: StorageBoundUserManagerImpl): UserManager

    @Binds
    @Singleton
    abstract fun bindStorageBoundUserManager(impl: StorageBoundUserManagerImpl): StorageBoundUserManager
}
