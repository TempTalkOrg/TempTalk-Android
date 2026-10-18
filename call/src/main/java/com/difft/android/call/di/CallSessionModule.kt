package com.difft.android.call.di

import com.difft.android.call.session.CallSessionComponentsFactory
import com.difft.android.call.session.CallSessionDispatchers
import com.difft.android.call.session.DefaultCallSessionComponentsFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object CallSessionModule {

    /** `main` is `Main.immediate`: the exact dispatcher `viewModelScope` gave every session collaborator. */
    @Provides
    @Singleton
    fun provideDispatchers(): CallSessionDispatchers =
        CallSessionDispatchers(default = Dispatchers.Default, main = Dispatchers.Main.immediate)

    @Provides
    fun provideComponentsFactory(impl: DefaultCallSessionComponentsFactory): CallSessionComponentsFactory = impl
}
