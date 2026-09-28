package com.difft.android.network.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
abstract class AuthModule {
    @Binds
    @Singleton
    abstract fun bindAuthTokenProvider(impl: AuthTokenManager): AuthTokenProvider
}
