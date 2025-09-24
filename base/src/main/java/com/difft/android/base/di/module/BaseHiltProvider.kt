package com.difft.android.base.di.module

import com.difft.android.base.utils.application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object BaseHiltProvider {
    @Provides
    @Named("application")
    fun provideApplicationCoroutineScope(): CoroutineScope = application
}