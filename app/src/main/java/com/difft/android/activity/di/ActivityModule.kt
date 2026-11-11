package com.difft.android.activity.di

import com.difft.android.activity.ActivityProviderImpl
import com.difft.android.base.activity.ActivityProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityModule {
    
    @Binds
    @Singleton
    abstract fun bindActivityProvider(impl: ActivityProviderImpl): ActivityProvider
}
