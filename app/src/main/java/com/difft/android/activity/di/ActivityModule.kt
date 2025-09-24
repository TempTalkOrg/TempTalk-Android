package com.difft.android.activity.di

import com.difft.android.activity.ActivityProviderImpl
import com.difft.android.base.activity.ActivityProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Activity相关的Hilt模块
 * 提供ActivityProvider的绑定
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityModule {
    
    @Binds
    @Singleton
    abstract fun bindActivityProvider(impl: ActivityProviderImpl): ActivityProvider
}
