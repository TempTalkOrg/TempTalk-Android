package com.difft.android.call.di

import com.difft.android.base.utils.ICallServiceUrlsProvider
import com.difft.android.call.manager.CallServiceUrlManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CallBindsModule {
    @Binds
    @Singleton
    abstract fun bindCallServiceUrlsProvider(
        impl: CallServiceUrlManager,
    ): ICallServiceUrlsProvider
}
