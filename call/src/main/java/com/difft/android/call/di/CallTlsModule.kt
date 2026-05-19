package com.difft.android.call.di

import android.content.Context
import com.difft.android.call.core.CallTlsProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CallTlsModule {
    @Provides
    @Singleton
    fun provideCallTlsProvider(
        @ApplicationContext context: Context,
    ): CallTlsProvider = CallTlsProvider(context)
}
